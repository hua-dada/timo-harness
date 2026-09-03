package com.apeloa.agent.web.files;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.apeloa.agent.workspace.files.WorkspaceFileService;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
// Boot 4：@AutoConfigureMockMvc 从 spring-boot-test-autoconfigure 迁到 webmvc 测试模块
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * M1-12：文件 REST 路由端到端（MockMvc + 真实 WorkspaceFileService，临时沙箱根）。
 * 校验状态码、错误文案、Content-Disposition/Cache-Control 与 /files、/api/files 双前缀。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FilesControllerTest {

    /** 开发态默认用户（application.yml 的 app.security.dev-user.id）。 */
    private static final String USER = "dev";

    private static final Path SANDBOX_ROOT = createTempRoot();

    @DynamicPropertySource
    static void sandboxRoot(DynamicPropertyRegistry registry) {
        registry.add("app.sandbox.root", SANDBOX_ROOT::toString);
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private WorkspaceFileService files;

    private Path workspace;

    @BeforeEach
    void resetWorkspace() throws IOException {
        workspace = files.workspaceOf(USER);
        deleteRecursively(workspace);
        Files.createDirectories(workspace);
    }

    @AfterAll
    static void cleanUp() throws IOException {
        deleteRecursively(SANDBOX_ROOT);
    }

    private void write(String rel, String content) throws IOException {
        Path p = workspace.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content, StandardCharsets.UTF_8);
    }

    @Test
    void 列目录过滤隐藏项且_api_files_前缀等价() throws Exception {
        write("b.txt", "b");
        Files.createDirectories(workspace.resolve("adir"));
        Files.createDirectories(workspace.resolve(".hidden"));

        for (String prefix : new String[] {"/files", "/api/files"}) {
            mvc.perform(get(prefix + "/list").param("path", "/"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.entries.length()").value(2))
                    .andExpect(jsonPath("$.entries[0].name").value("adir"))
                    .andExpect(jsonPath("$.entries[0].type").value("dir"))
                    .andExpect(jsonPath("$.entries[1].name").value("b.txt"))
                    .andExpect(jsonPath("$.entries[1].type").value("file"));
        }
    }

    @Test
    void 读写文件与_PATCH_别名() throws Exception {
        mvc.perform(put("/files/content").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"path\":\"sub/a.txt\",\"content\":\"你好\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.path").value("sub/a.txt"))
                .andExpect(jsonPath("$.ok").value(true));
        assertThat(workspace.resolve("sub/a.txt")).hasContent("你好");

        mvc.perform(get("/files/content").param("path", "sub/a.txt"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value("你好"));

        mvc.perform(patch("/api/files/content").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"path\":\"sub/a.txt\",\"content\":\"改了\"}"))
                .andExpect(status().isOk());
        assertThat(workspace.resolve("sub/a.txt")).hasContent("改了");
    }

    @Test
    void 删文件与清空工作空间保留隐藏项() throws Exception {
        write("a.txt", "a");
        write("d/inner.txt", "b");
        Files.createDirectories(workspace.resolve(".pi"));

        mvc.perform(delete("/files/content").param("path", "a.txt"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));

        mvc.perform(delete("/files/workspace"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.removed[0]").value("d"));
        assertThat(workspace.resolve(".pi")).exists();
        assertThat(workspace.resolve("d")).doesNotExist();
    }

    @Test
    void 越权路径与不存在与过大分别返回400_404_413() throws Exception {
        Files.write(workspace.resolve("big.bin"), new byte[(int) WorkspaceFileService.MAX_BYTES + 1]);

        mvc.perform(get("/files/content").param("path", "../../etc/passwd"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("路径越权"));
        mvc.perform(get("/files/list").param("path", "nope"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("目录不存在"));
        mvc.perform(get("/files/content").param("path", "missing.txt"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("文件不存在"));
        mvc.perform(get("/files/content").param("path", "big.bin"))
                .andExpect(status().isContentTooLarge())
                .andExpect(jsonPath("$.error").value("文件过大（>1MB）"));
        mvc.perform(delete("/files/content").param("path", "/"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("不能删除根目录"));
    }

    @Test
    void 非法身份视为未登录返回401() throws Exception {
        mvc.perform(get("/files/list").header("X-User-Id", "..").param("path", "/"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("未登录"));
    }

    @Test
    void 下载返回二进制流与RFC5987中文文件名() throws Exception {
        write("报告.txt", "内容");

        mvc.perform(get("/files/download").param("path", "报告.txt"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_OCTET_STREAM))
                // Spring 7 的 ContentDisposition 会同时带 RFC 2047 回退参数 filename="=?UTF-8?Q?...?="
                // 与 RFC 5987 的 filename*；浏览器按 RFC 6266 优先取 filename*（前端 a.download 又优先于 header）。
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("attachment;")))
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("filename*=UTF-8''")))
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("%E6%8A%A5%E5%91%8A")))
                .andExpect(content().bytes("内容".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void 预览按扩展名给MIME且带no_store并支持嵌套与中文路径() throws Exception {
        write("page.html", "<html>x</html>");
        write("assets/style.css", "body{}");
        write("图.svg", "<svg/>");

        mvc.perform(get("/files/preview/page.html"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "text/html; charset=utf-8"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().string("<html>x</html>"));
        mvc.perform(get("/files/preview/assets/style.css"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "text/css; charset=utf-8"));
        mvc.perform(get("/files/preview/图.svg"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/svg+xml"));
        mvc.perform(get("/api/files/preview/nope.html"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("文件不存在"));
    }

    @Test
    void 上传落根目录同名改名且缺文件报400() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "报告.txt", "text/plain", "内容".getBytes(StandardCharsets.UTF_8));

        mvc.perform(multipart("/files/upload").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.path").value("/报告.txt"))
                .andExpect(jsonPath("$.ok").value(true));
        mvc.perform(multipart("/api/files/upload").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.path").value("/报告-1.txt"));
        mvc.perform(multipart("/files/upload"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("缺少文件"));
    }

    @Test
    void 上传超过10MB返回413() throws Exception {
        MockMultipartFile tooBig = new MockMultipartFile(
                "file", "big.bin", "application/octet-stream",
                new byte[(int) WorkspaceFileService.MAX_UPLOAD_BYTES + 1]);

        mvc.perform(multipart("/files/upload").file(tooBig))
                .andExpect(status().isContentTooLarge())
                .andExpect(jsonPath("$.error").value("文件过大（>10MB）"));
    }

    private static Path createTempRoot() {
        try {
            return Files.createTempDirectory("apeloa-files-test-");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }
}
