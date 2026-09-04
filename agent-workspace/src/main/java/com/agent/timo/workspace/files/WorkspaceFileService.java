package com.agent.timo.workspace.files;

import com.agent.timo.core.files.PathEscapeException;
import com.agent.timo.core.files.SafePath;
import com.agent.timo.workspace.SandboxPaths;
import com.agent.timo.workspace.files.WorkspaceFileException.Kind;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 用户 workspace 文件操作（移植自源项目 apps/server/src/files/router.ts + upload.ts 的业务部分）。
 *
 * <p>安全三件套：调用方鉴权拿到 userId → {@link SafePath#resolveUserPath} 防穿越 → 大小上限。
 * 所有路径一律经 resolveUserPath 落在 {@code <sandboxRoot>/<userId>/workspace} 内，
 * 绝对路径与 {@code ../} 都无法外逃（源 spec 无多租户，去掉 tenant 层）。
 *
 * <p>与源项目的偏差（均为 Java 版有意选择）：
 * <ul>
 *   <li>不含加解密（CRYPTO_* + rustfs 外部服务）分支：M1~M3 无此任务，上传落明文、下载回明文；</li>
 *   <li>列目录按「目录优先 + 名称」排序（源为 readdir 顺序，ext4 上近似随机，前端不排序）；</li>
 *   <li>上传落盘用 {@code CREATE_NEW}：改名探测与落盘之间被抢名时报错，绝不覆盖已有文件；</li>
 *   <li>文件名清洗白名单放宽为 {@code \p{L}\p{N}_ .-}（源窄白名单是外部加解密服务的限制）。</li>
 * </ul>
 *
 * <p>本类不经容器：文件读写由 JVM 进程直接落宿主盘（与 M1-9 沙箱记录一致）；
 * 目录属主/权限由 {@code SecureDirs} 在 {@code SandboxManager.acquire} 时收口。
 */
public class WorkspaceFileService {

    /** 文本读写上限：1MB（源 MAX_BYTES）。 */
    public static final long MAX_BYTES = 1024L * 1024;

    /** 上传 / 下载 / 预览上限：10MB（源 MAX_UPLOAD_BYTES）。 */
    public static final long MAX_UPLOAD_BYTES = 10L * 1024 * 1024;

    /** 同名自动改名的最大尝试次数（name-1.ext … name-9999.ext），源 MAX_DEDUP_TRIES。 */
    static final int MAX_DEDUP_TRIES = 10_000;

    /** 落盘文件名长度上限（超长截断但保留扩展名），源同值。 */
    private static final int MAX_FILENAME_LENGTH = 100;

    /** 文件名白名单外字符（一律删除）：路径分隔符、Windows 保留字符、控制符残留、全角标点等。 */
    private static final Pattern UNSAFE_FILENAME_CHARS = Pattern.compile("[^\\p{L}\\p{N}_ .-]");

    /**
     * 合法 userId：首字符非点，仅 {@code A-Za-z0-9._-}，≤64 字符。
     *
     * <p>userId 直接作为 {@code <sandboxRoot>/<userId>} 路径段，不校验则 ".." 之类的身份可把整个
     * workspace 根挪到 sandboxRoot 之外（SafePath 只约束 workspace 内的相对路径，管不到根本身）。
     * M1-3/M1-13 接入 JWT/OIDC 时须把 subject 规范化到此字符集。
     */
    private static final Pattern VALID_USER_ID = Pattern.compile("[A-Za-z0-9_-][A-Za-z0-9._-]{0,63}");

    /** 列目录排序：目录优先，再按名称（忽略大小写，同名回落到区分大小写以保证全序）。 */
    private static final Comparator<Entry> ENTRY_ORDER =
            Comparator.comparingInt((Entry e) -> "dir".equals(e.type()) ? 0 : 1)
                    .thenComparing(Entry::name, String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(Entry::name);

    private final SandboxPaths paths;

    public WorkspaceFileService(SandboxPaths paths) {
        this.paths = paths;
    }

    /** 目录项（{@code type} 为 "file" | "dir"，对齐前端 files-api.ts 的 FileEntry）。 */
    public record Entry(String name, String type) {
    }

    /** 下载结果：文件名（用于 Content-Disposition）+ 字节。 */
    public record Download(String filename, byte[] bytes) {
    }

    /** 预览结果：MIME + 字节。 */
    public record Preview(String contentType, byte[] bytes) {
    }

    /** userId 是否可安全用作路径段（身份来源侧也应校验，非法即视为未登录）。 */
    public static boolean isValidUserId(String userId) {
        return userId != null && VALID_USER_ID.matcher(userId).matches();
    }

    /** 某用户 workspace 根（宿主绝对路径）；userId 非法直接抛错，绝不落到 sandboxRoot 之外。 */
    public Path workspaceOf(String userId) {
        if (!isValidUserId(userId)) {
            throw new IllegalArgumentException("非法 userId：" + userId);
        }
        return paths.userWorkspaceDir(userId).toAbsolutePath().normalize();
    }

    /** 解析用户输入相对路径到 workspace 内；越权抛 {@link PathEscapeException}（web 层 → 400 路径越权）。 */
    private Path resolve(String userId, String rel) {
        return SafePath.resolveUserPath(workspaceOf(userId), rel);
    }

    /**
     * 列目录（单层）。过滤隐藏项但放行 {@code .pi}（让用户可查看 agent 内部状态）。
     * 目录不存在或目标不是目录 → {@link Kind#NOT_FOUND}「目录不存在」（对齐源 404）。
     *
     * <p>偏差：workspace 根不存在时按需创建。源项目由 {@code SandboxManager.acquire} 在开会话时
     * 建目录，Java 版 M1-12 先于 M1-6/M1-11 落地，不兜底则新用户列根目录必然 404。
     */
    public List<Entry> list(String userId, String rel) {
        Path workspace = workspaceOf(userId);
        Path dir = SafePath.resolveUserPath(workspace, rel);
        if (dir.equals(workspace)) {
            try {
                Files.createDirectories(workspace);
            } catch (IOException e) {
                // 建不出来就让下面的 Files.list 抛 → 404，不额外制造异常类型
            }
        }
        try (Stream<Path> children = Files.list(dir)) {
            return children
                    .map(p -> new Entry(p.getFileName().toString(),
                            Files.isDirectory(p) ? "dir" : "file"))
                    .filter(e -> visible(e.name()))
                    .sorted(ENTRY_ORDER)
                    .toList();
        } catch (IOException | UncheckedIOException e) {
            throw new WorkspaceFileException(Kind.NOT_FOUND, "目录不存在", e);
        }
    }

    /** 隐藏项判定与源 list 路由一致：以 . 开头即隐藏，仅放行 .pi。 */
    private static boolean visible(String name) {
        return !name.startsWith(".") || ".pi".equals(name);
    }

    /**
     * 读文本（UTF-8；>1MB 拒绝）。非法字节按 U+FFFD 替换而不报错，对齐 Node
     * {@code readFile(file,"utf8")}（{@code new String(byte[], UTF_8)} 同为 REPLACE 策略）。
     */
    public String readText(String userId, String rel) {
        Path file = resolve(userId, rel);
        BasicFileAttributes attrs = attrsOrNotFound(file, "文件不存在");
        if (!attrs.isRegularFile()) {
            throw new WorkspaceFileException(Kind.NOT_A_FILE, "非文件");
        }
        if (attrs.size() > MAX_BYTES) {
            throw new WorkspaceFileException(Kind.TOO_LARGE, "文件过大（>1MB）");
        }
        try {
            return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new WorkspaceFileException(Kind.NOT_FOUND, "文件不存在", e);
        }
    }

    /**
     * 写文本（UTF-8；父目录自动建）。上限比较用字符数而非字节数，与源
     * {@code content.length > MAX_BYTES}（JS UTF-16 code unit）一致，避免双端判定不一。
     */
    public void writeText(String userId, String rel, String content) {
        if (content.length() > MAX_BYTES) {
            throw new WorkspaceFileException(Kind.TOO_LARGE, "内容过大（>1MB）");
        }
        Path file = resolve(userId, rel);
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.write(file, content.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new WorkspaceFileException(Kind.IO_FAILED, "写入失败：" + e, e);
        }
    }

    /** 删文件 / 递归删目录；拒绝删 workspace 根（源 400「不能删除根目录」）。 */
    public void delete(String userId, String rel) {
        Path workspace = workspaceOf(userId);
        Path target = SafePath.resolveUserPath(workspace, rel);
        if (target.equals(workspace)) {
            throw new WorkspaceFileException(Kind.ROOT_PROTECTED, "不能删除根目录");
        }
        BasicFileAttributes attrs = attrsOrNotFound(target, "路径不存在");
        try {
            if (attrs.isDirectory()) {
                deleteRecursively(target);
            } else {
                Files.delete(target);
            }
        } catch (IOException | UncheckedIOException e) {
            throw new WorkspaceFileException(Kind.IO_FAILED, "删除失败：" + e, e);
        }
    }

    /**
     * 清空工作空间：删根下所有非隐藏条目，保留 {@code .pi} 等以 . 开头的隐藏文件 / 目录；
     * 返回被删名单。任一条目失败即整体 500（源同为中途抛出，不做部分成功语义）。
     */
    public List<String> clearWorkspace(String userId) {
        Path workspace = workspaceOf(userId);
        List<String> removed = new ArrayList<>();
        try (Stream<Path> children = Files.list(workspace)) {
            for (Path child : (Iterable<Path>) children::iterator) {
                String name = child.getFileName().toString();
                if (name.startsWith(".")) {
                    continue; // 隐藏项一律保留（.pi / .gitignore 等）
                }
                // NOFOLLOW：指向目录的符号链接按链接删（unlink），不递归进目标目录
                if (Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) {
                    deleteRecursively(child);
                } else {
                    Files.delete(child);
                }
                removed.add(name);
            }
        } catch (IOException | UncheckedIOException e) {
            throw new WorkspaceFileException(Kind.IO_FAILED, "清空失败：" + e, e);
        }
        return removed;
    }

    /** 下载（仅普通文件；>10MB 拒绝）。 */
    public Download download(String userId, String rel) {
        Path file = resolve(userId, rel);
        BasicFileAttributes attrs = attrsOrNotFound(file, "文件不存在");
        if (!attrs.isRegularFile()) {
            throw new WorkspaceFileException(Kind.NOT_A_FILE, "非文件，无法下载");
        }
        if (attrs.size() > MAX_UPLOAD_BYTES) {
            throw new WorkspaceFileException(Kind.TOO_LARGE, "文件过大（>10MB）");
        }
        try {
            return new Download(file.getFileName().toString(), Files.readAllBytes(file));
        } catch (IOException e) {
            throw new WorkspaceFileException(Kind.IO_FAILED, "下载失败：" + e, e);
        }
    }

    /** 预览（仅普通文件；>10MB 拒绝）：MIME 按扩展名推断，读失败一律 404（对齐源 catch-all）。 */
    public Preview preview(String userId, String rel) {
        Path file = resolve(userId, rel);
        BasicFileAttributes attrs = attrsOrNotFound(file, "文件不存在");
        if (!attrs.isRegularFile()) {
            throw new WorkspaceFileException(Kind.NOT_A_FILE, "非文件");
        }
        if (attrs.size() > MAX_UPLOAD_BYTES) {
            throw new WorkspaceFileException(Kind.TOO_LARGE, "文件过大（>10MB）");
        }
        try {
            String name = file.getFileName().toString();
            return new Preview(PreviewMimeTypes.forFilename(name), Files.readAllBytes(file));
        } catch (IOException e) {
            throw new WorkspaceFileException(Kind.NOT_FOUND, "文件不存在", e);
        }
    }

    /**
     * 上传落 workspace 根：清洗文件名 → 同名自动改名 → 流式落盘；返回带 {@code /} 前缀的相对路径
     * （与前端 files-api.ts 的约定一致）。落盘失败删半成品，绝不留下截断文件。
     */
    public String upload(String userId, String rawFilename, InputStream in) {
        Path workspace = workspaceOf(userId);
        try {
            Files.createDirectories(workspace);
        } catch (IOException e) {
            throw new WorkspaceFileException(Kind.IO_FAILED, "上传失败：" + e, e);
        }
        Path target = resolveUniquePath(workspace, sanitizeFilename(rawFilename));
        try (OutputStream out = Files.newOutputStream(
                target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            in.transferTo(out);
        } catch (IOException e) {
            try {
                Files.deleteIfExists(target);
            } catch (IOException ignored) {
                // 半成品清理失败不掩盖原始错误
            }
            throw new WorkspaceFileException(Kind.IO_FAILED, "上传失败：" + e, e);
        }
        return "/" + workspace.relativize(target).toString().replace('\\', '/');
    }

    /**
     * 在 workspace 内找不冲突的路径：同名则 name-1.ext、name-2.ext …。
     * 存在性判定用 NOFOLLOW，悬空符号链接视为占用（防经链接写到 workspace 外）。
     */
    private static Path resolveUniquePath(Path workspace, String name) {
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 0; i < MAX_DEDUP_TRIES; i++) {
            String candidate = i == 0 ? name : stem + "-" + i + ext;
            Path abs;
            try {
                abs = SafePath.resolveUserPath(workspace, "/" + candidate);
            } catch (PathEscapeException e) {
                throw new WorkspaceFileException(Kind.CONFLICT, "同名文件过多，无法自动改名", e);
            }
            if (!Files.exists(abs, LinkOption.NOFOLLOW_LINKS)) {
                return abs;
            }
        }
        throw new WorkspaceFileException(Kind.CONFLICT, "同名文件过多，无法自动改名");
    }

    /**
     * 清洗客户端 filename（移植源 upload.ts 的 sanitizeFilename）：去路径成分 → 去控制字符 →
     * 白名单清洗 → 去首尾点 → 超长截断保留扩展名。任何步骤后为空都回落 {@code "upload"}，
     * 绝不返回空串或带路径成分的名字。
     */
    static String sanitizeFilename(String raw) {
        String base = raw == null ? "" : raw;
        int slash = Math.max(base.lastIndexOf('/'), base.lastIndexOf('\\'));
        if (slash >= 0) {
            base = base.substring(slash + 1);
        }
        String cleaned = UNSAFE_FILENAME_CHARS.matcher(stripControlChars(base)).replaceAll("")
                .trim()
                .replaceAll("^\\.+|\\.+$", "");
        if (cleaned.isEmpty()) {
            return "upload";
        }
        if (cleaned.length() <= MAX_FILENAME_LENGTH) {
            return cleaned;
        }
        int dot = cleaned.lastIndexOf('.');
        if (dot <= 0) {
            return cleaned.substring(0, MAX_FILENAME_LENGTH);
        }
        String ext = cleaned.substring(dot);
        return cleaned.substring(0, Math.max(1, MAX_FILENAME_LENGTH - ext.length())) + ext;
    }

    /** 去控制字符（U+0000..U+001F、U+007F）：可污染日志、欺骗路径显示。 */
    private static String stripControlChars(String s) {
        StringBuilder out = new StringBuilder(s.length());
        s.codePoints()
                .filter(cp -> cp >= 0x20 && cp != 0x7f)
                .forEach(out::appendCodePoint);
        return out.toString();
    }

    /** stat 一次；不存在 / 不可读一律按 NOT_FOUND 抛出（message 随调用点，对齐源各分支文案）。 */
    private static BasicFileAttributes attrsOrNotFound(Path path, String message) {
        try {
            return Files.readAttributes(path, BasicFileAttributes.class);
        } catch (IOException e) {
            throw new WorkspaceFileException(Kind.NOT_FOUND, message, e);
        }
    }

    /** 递归删（自底向上；不跟随符号链接，链接本身被删除）。 */
    private static void deleteRecursively(Path root) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }
}
