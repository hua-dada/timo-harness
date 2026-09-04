package com.agent.timo.workspace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 沙箱目录权限收口（移植自源项目 sandbox/secure.ts）：per-user / per-uid 目录
 * chown 到 uid + chmod 0700；祖先目录 0711（可穿越不可列）。
 *
 * <p>Java 进程在 Linux 生产以 root（CAP_CHOWN/CAP_FOWNER）运行；非 POSIX 文件系统
 * （Windows/NTFS）或无权限时静默降级——uid 隔离只在 Linux 生产生效，开发用 local 模式。
 * 幂等：属主匹配时跳过递归 chown，避免每次 acquire 全树扫描。
 */
public final class SecureDirs {

    private static final Logger log = LoggerFactory.getLogger(SecureDirs.class);

    private static final Set<PosixFilePermission> OWNER_ONLY =
            PosixFilePermissions.fromString("rwx------");
    private static final Set<PosixFilePermission> TRAVERSABLE =
            PosixFilePermissions.fromString("rwx--x--x");

    private SecureDirs() {
    }

    /**
     * 用户目录树收口：userRoot 0700 + chown uid；祖先（sandboxRoot）0711。
     * 仅当属主不匹配时递归 chown（幂等）。
     */
    public static void secureUserDir(Path userRoot, long uid) {
        chmodQuietly(userRoot, OWNER_ONLY);
        chmodQuietly(userRoot.getParent(), TRAVERSABLE);
        chownIfMismatched(userRoot, uid, true);
    }

    /** per-uid 短 TMPDIR 收口：uid 目录 0700 + chown uid；父 tmp-by-uid 0711（防枚举 uid）。 */
    public static void secureTmpByUidDir(Path uidTmpDir, long uid) {
        chmodQuietly(uidTmpDir, OWNER_ONLY);
        chmodQuietly(uidTmpDir.getParent(), TRAVERSABLE);
        chownIfMismatched(uidTmpDir, uid, true);
    }

    private static void chmodQuietly(Path dir, Set<PosixFilePermission> perms) {
        if (dir == null) {
            return;
        }
        try {
            Files.setPosixFilePermissions(dir, perms);
        } catch (UnsupportedOperationException | IOException e) {
            log.debug("chmod 跳过（非 POSIX 或无权限）：{} ({})", dir, e.toString());
        }
    }

    private static void chownIfMismatched(Path dir, long uid, boolean recursive) {
        PosixFileAttributeView view = Files.getFileAttributeView(
                dir, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (view == null) {
            return; // 非 POSIX 文件系统（Windows/NTFS）
        }
        try {
            long owner = (Long) Files.getAttribute(dir, "unix:uid", LinkOption.NOFOLLOW_LINKS);
            if (owner == uid) {
                return; // 幂等：属主已匹配
            }
            chownTree(dir, uid, recursive ? 4 : 0);
        } catch (IOException | UnsupportedOperationException e) {
            log.debug("chown 跳过（无权限或非 POSIX）：{} ({})", dir, e.toString());
        }
    }

    /** 递归 chown（深度上限防符号链接环）。gid 取同值（数值私有组）。
     *  数值 uid/gid 直接写 "unix:uid"/"unix:gid" 扩展属性（Integer → lchown），
     *  不经 UserPrincipalLookupService——后者按名字查 /etc/passwd，数值 uid 无条目会失败。 */
    private static void chownTree(Path dir, long uid, int depth) {
        try {
            Files.setAttribute(dir, "unix:uid", (int) uid, LinkOption.NOFOLLOW_LINKS);
            Files.setAttribute(dir, "unix:gid", (int) uid, LinkOption.NOFOLLOW_LINKS);
        } catch (UnsupportedOperationException | IOException e) {
            log.debug("chown 跳过（无权限或非 POSIX）：{} ({})", dir, e.toString());
            return;
        }
        if (depth <= 0) {
            return;
        }
        try (Stream<Path> children = Files.list(dir)) {
            for (Path child : (Iterable<Path>) children::iterator) {
                chownTree(child, uid, Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS) ? depth - 1 : 0);
            }
        } catch (IOException e) {
            log.debug("递归 chown 中断（子项列举失败）：{} ({})", dir, e.toString());
        }
    }
}
