package com.apeloa.agent.workspace.files;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.apeloa.agent.core.files.PathEscapeException;
import com.apeloa.agent.workspace.SandboxPaths;
import com.apeloa.agent.workspace.files.WorkspaceFileException.Kind;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * M1-12：workspace 文件操作单测（对齐源项目 files/router.ts + upload.ts 语义）。
 */
class WorkspaceFileServiceTest {

    private static final String USER = "u1";

    @TempDir
    Path sandboxRoot;

    private WorkspaceFileService files;
    private Path workspace;

    @BeforeEach
    void setUp() throws IOException {
        SandboxPaths paths = new SandboxPaths(sandboxRoot);
        files = new WorkspaceFileService(paths);
        workspace = paths.userWorkspaceDir(USER);
        Files.createDirectories(workspace);
    }

    private void write(String rel, String content) throws IOException {
        Path p = workspace.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content, StandardCharsets.UTF_8);
    }

    @Test
    void 列目录过滤隐藏项但放行_pi_且目录优先排序() throws IOException {
        write("b.txt", "b");
        write("a.txt", "a");
        Files.createDirectories(workspace.resolve("zdir"));
        Files.createDirectories(workspace.resolve(".pi"));
        Files.createDirectories(workspace.resolve(".hidden"));
        write(".secret", "x");

        List<WorkspaceFileService.Entry> entries = files.list(USER, "/");

        assertThat(entries).containsExactly(
                new WorkspaceFileService.Entry(".pi", "dir"),
                new WorkspaceFileService.Entry("zdir", "dir"),
                new WorkspaceFileService.Entry("a.txt", "file"),
                new WorkspaceFileService.Entry("b.txt", "file"));
    }

    @Test
    void 新用户列根目录自动建目录返回空列表() {
        assertThat(files.list("brandnew", "/")).isEmpty();
        assertThat(new SandboxPaths(sandboxRoot).userWorkspaceDir("brandnew")).exists();
    }

    @Test
    void 列不存在目录与对文件列目录都报目录不存在() throws IOException {
        write("f.txt", "x");

        assertThatThrownBy(() -> files.list(USER, "nope"))
                .isInstanceOf(WorkspaceFileException.class)
                .hasMessage("目录不存在");
        assertThat(kindOf(() -> files.list(USER, "nope"))).isEqualTo(Kind.NOT_FOUND);
        assertThatThrownBy(() -> files.list(USER, "f.txt"))
                .isInstanceOf(WorkspaceFileException.class)
                .hasMessage("目录不存在");
    }

    @Test
    void 读文件正常且非法字节按替换字符解码不报错() throws IOException {
        write("a.txt", "你好");
        Files.write(workspace.resolve("bin"), new byte[] {(byte) 0xC3, (byte) 0x28});

        assertThat(files.readText(USER, "/a.txt")).isEqualTo("你好");
        assertThat(files.readText(USER, "bin")).contains("�");
    }

    @Test
    void 读超过1MB的文件与读目录分别报413与非文件() throws IOException {
        Files.write(workspace.resolve("big.bin"), new byte[(int) WorkspaceFileService.MAX_BYTES + 1]);
        Files.createDirectories(workspace.resolve("d"));

        assertThatThrownBy(() -> files.readText(USER, "big.bin"))
                .isInstanceOf(WorkspaceFileException.class)
                .hasMessage("文件过大（>1MB）");
        assertThat(kindOf(() -> files.readText(USER, "big.bin"))).isEqualTo(Kind.TOO_LARGE);
        assertThatThrownBy(() -> files.readText(USER, "d"))
                .isInstanceOf(WorkspaceFileException.class)
                .hasMessage("非文件");
        assertThat(kindOf(() -> files.readText(USER, "d"))).isEqualTo(Kind.NOT_A_FILE);
        assertThatThrownBy(() -> files.readText(USER, "missing"))
                .isInstanceOf(WorkspaceFileException.class)
                .hasMessage("文件不存在");
    }

    @Test
    void 写文件自动建父目录且超1MB内容拒绝() {
        files.writeText(USER, "sub/deep/a.txt", "内容");

        assertThat(workspace.resolve("sub/deep/a.txt")).hasContent("内容");
        assertThatThrownBy(() -> files.writeText(USER, "x.txt", "x".repeat((int) WorkspaceFileService.MAX_BYTES + 1)))
                .isInstanceOf(WorkspaceFileException.class)
                .hasMessage("内容过大（>1MB）");
    }

    @Test
    void 路径穿越被拦截而绝对路径被视为workspace内相对路径() {
        assertThatThrownBy(() -> files.readText(USER, "../../etc/passwd"))
                .isInstanceOf(PathEscapeException.class);
        assertThatThrownBy(() -> files.readText(USER, "a/../../../outside"))
                .isInstanceOf(PathEscapeException.class);

        files.writeText(USER, "/etc/passwd", "内部");
        assertThat(workspace.resolve("etc/passwd")).hasContent("内部");
    }

    @Test
    void 非法userId直接拒绝且不落到沙箱根之外() {
        assertThat(WorkspaceFileService.isValidUserId("u1")).isTrue();
        assertThat(WorkspaceFileService.isValidUserId("..")).isFalse();
        assertThat(WorkspaceFileService.isValidUserId(".hidden")).isFalse();
        assertThat(WorkspaceFileService.isValidUserId("a/b")).isFalse();
        assertThat(WorkspaceFileService.isValidUserId(null)).isFalse();
        assertThatThrownBy(() -> files.list("../..", "/")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 删文件与递归删目录成功而删根被拒() throws IOException {
        write("a.txt", "a");
        write("d/inner/b.txt", "b");

        files.delete(USER, "a.txt");
        files.delete(USER, "d");

        assertThat(workspace.resolve("a.txt")).doesNotExist();
        assertThat(workspace.resolve("d")).doesNotExist();
        assertThatThrownBy(() -> files.delete(USER, "/"))
                .isInstanceOf(WorkspaceFileException.class)
                .hasMessage("不能删除根目录");
        assertThat(kindOf(() -> files.delete(USER, "/"))).isEqualTo(Kind.ROOT_PROTECTED);
        assertThatThrownBy(() -> files.delete(USER, "missing"))
                .isInstanceOf(WorkspaceFileException.class)
                .hasMessage("路径不存在");
    }

    @Test
    void 清空工作空间保留隐藏项并返回删除名单() throws IOException {
        write("a.txt", "a");
        write("d/inner.txt", "b");
        Files.createDirectories(workspace.resolve(".pi"));
        write(".gitignore", "x");

        List<String> removed = files.clearWorkspace(USER);

        assertThat(removed).containsExactlyInAnyOrder("a.txt", "d");
        assertThat(workspace.resolve(".pi")).exists();
        assertThat(workspace.resolve(".gitignore")).exists();
        assertThat(workspace.resolve("a.txt")).doesNotExist();
        assertThat(workspace.resolve("d")).doesNotExist();
    }

    @Test
    void 下载返回文件名与字节而目录报非文件() throws IOException {
        write("报告.txt", "内容");
        Files.createDirectories(workspace.resolve("d"));

        WorkspaceFileService.Download d = files.download(USER, "报告.txt");

        assertThat(d.filename()).isEqualTo("报告.txt");
        assertThat(new String(d.bytes(), StandardCharsets.UTF_8)).isEqualTo("内容");
        assertThatThrownBy(() -> files.download(USER, "d"))
                .isInstanceOf(WorkspaceFileException.class)
                .hasMessage("非文件，无法下载");
    }

    @Test
    void 预览按扩展名给MIME且未识别回落二进制流() throws IOException {
        write("page.html", "<html></html>");
        write("data.bin", "x");

        assertThat(files.preview(USER, "page.html").contentType()).isEqualTo("text/html; charset=utf-8");
        assertThat(files.preview(USER, "data.bin").contentType()).isEqualTo(PreviewMimeTypes.DEFAULT);
        assertThat(PreviewMimeTypes.forFilename("A.PNG")).isEqualTo("image/png");
        assertThat(PreviewMimeTypes.forFilename(".bashrc")).isEqualTo(PreviewMimeTypes.DEFAULT);
        assertThat(PreviewMimeTypes.forFilename("noext")).isEqualTo(PreviewMimeTypes.DEFAULT);
    }

    @Test
    void 上传落根目录并对同名自动改名() {
        String first = files.upload(USER, "报告.txt", stream("a"));
        String second = files.upload(USER, "报告.txt", stream("b"));
        String third = files.upload(USER, "报告.txt", stream("c"));

        assertThat(first).isEqualTo("/报告.txt");
        assertThat(second).isEqualTo("/报告-1.txt");
        assertThat(third).isEqualTo("/报告-2.txt");
        assertThat(workspace.resolve("报告-1.txt")).hasContent("b");
    }

    @Test
    void 上传文件名清洗去路径成分与非法字符空名回落upload() {
        assertThat(files.upload(USER, "../../etc/passwd", stream("x"))).isEqualTo("/passwd");
        assertThat(files.upload(USER, "a<>:\"|?*b.txt", stream("x"))).isEqualTo("/ab.txt");
        assertThat(files.upload(USER, "...", stream("x"))).isEqualTo("/upload");
        assertThat(WorkspaceFileService.sanitizeFilename("a" + (char) 1 + (char) 0x7f + "b.txt")).isEqualTo("ab.txt");
        assertThat(WorkspaceFileService.sanitizeFilename("x".repeat(120) + ".txt"))
                .hasSize(100)
                .endsWith(".txt");
    }

    @Test
    void 上传不覆盖已有文件() throws IOException {
        write("keep.txt", "原有");

        assertThat(files.upload(USER, "keep.txt", stream("新的"))).isEqualTo("/keep-1.txt");
        assertThat(workspace.resolve("keep.txt")).hasContent("原有");
    }

    private static ByteArrayInputStream stream(String content) {
        return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }

    /** 取操作抛出的 WorkspaceFileException 的 kind（web 层状态码映射的输入）。 */
    private static Kind kindOf(Runnable action) {
        try {
            action.run();
        } catch (WorkspaceFileException e) {
            return e.kind();
        }
        throw new AssertionError("期望抛出 WorkspaceFileException，实际未抛");
    }
}
