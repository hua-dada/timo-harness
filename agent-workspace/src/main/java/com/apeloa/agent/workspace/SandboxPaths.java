package com.apeloa.agent.workspace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 沙箱目录布局：每用户独立 workspace/sessions/home，落 {@code <sandboxRoot>/<userId>/}。
 * 移植自源项目 sandbox/paths.ts（spec 无多租户，去掉 tenant 层）。
 *
 * <p>宿主 sandboxRoot 整体挂载到共享容器 {@link #DATA_MOUNT}，容器内路径与宿主一一对应；
 * docker 模式 TMPDIR 用 per-uid 短路径（数字 uid 索引 21 字符，避开 UUID 长路径致
 * chromium 的 AF_UNIX sun_path[108] 越界——源项目实测踩坑）。
 */
public final class SandboxPaths {

    /** 容器内挂载点：宿主 sandboxRoot → /data。 */
    public static final String DATA_MOUNT = "/data";

    /** per-uid 短 TMPDIR 顶层索引目录名（sandboxRoot 下，与 \<userId\>/ 同级）。 */
    public static final String TMP_BY_UID_DIR = "tmp-by-uid";

    private final Path sandboxRoot;

    public SandboxPaths(Path sandboxRoot) {
        this.sandboxRoot = sandboxRoot.toAbsolutePath().normalize();
    }

    public Path sandboxRoot() {
        return sandboxRoot;
    }

    /** 某用户的工作区目录（宿主路径，持久）。 */
    public Path userWorkspaceDir(String userId) {
        return sandboxRoot.resolve(userId).resolve("workspace");
    }

    /** 某用户的 home 目录（宿主路径）：bash 的 HOME，承载可写状态（bash_history/git config）。 */
    public Path userHomeDir(String userId) {
        return sandboxRoot.resolve(userId).resolve("home");
    }

    /** 某用户根目录 {@code <sandboxRoot>/<userId>}（SecureDirs 对其做 0700 + chown）。 */
    public Path userRootDir(String userId) {
        return sandboxRoot.resolve(userId);
    }

    /** per-uid 短 TMPDIR（宿主路径）：docker 模式 bash 的 TMPDIR。 */
    public Path tmpByUidDir(long uid) {
        return sandboxRoot.resolve(TMP_BY_UID_DIR).resolve(Long.toString(uid));
    }

    /** 容器内某用户子目录绝对路径 {@code /data/<userId>/<sub>}（docker exec 的 -w/HOME 用）。 */
    public String containerUserDir(String userId, String sub) {
        return DATA_MOUNT + "/" + userId + "/" + sub;
    }

    /** 容器内 per-uid TMPDIR {@code /data/tmp-by-uid/<uid>}（docker exec 的 -e TMPDIR 用）。 */
    public String containerTmpByUidDir(long uid) {
        return DATA_MOUNT + "/" + TMP_BY_UID_DIR + "/" + uid;
    }

    /** 宿主 workspace 路径 → 容器内路径（挂载直映射）；不在 sandboxRoot 下则抛错。 */
    public String toContainerPath(Path hostPath) {
        Path p = hostPath.toAbsolutePath().normalize();
        if (!p.startsWith(sandboxRoot)) {
            throw new IllegalArgumentException("路径不在 sandboxRoot 内：" + p);
        }
        Path rel = sandboxRoot.relativize(p);
        // Windows 对相同路径的 relativize 会给出 nameCount=1 但字符串为空的"空路径"，两种都按根处理
        if (rel.getNameCount() == 0 || rel.toString().isEmpty()) {
            return DATA_MOUNT; // 根自身，不带尾斜杠
        }
        return DATA_MOUNT + "/" + rel.toString().replace('\\', '/');
    }

    /** 确保目录存在（recursive）。 */
    public static void ensureDir(Path dir) throws IOException {
        Files.createDirectories(dir);
    }
}
