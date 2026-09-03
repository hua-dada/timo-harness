package com.apeloa.agent.core.files;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * SafePath 单测：锁死路径越权防护不变量（../ 穿越 / 伪前缀 / 空字节 / Windows 反斜杠）。
 * 全量移植自源项目 apps/server/src/files/safe-path.test.ts。
 */
class SafePathTest {

    @TempDir
    static Path tmp;

    private static Path WS;

    @BeforeAll
    static void createWorkspace() throws IOException {
        Files.createDirectories(tmp.resolve("sandbox").resolve("ws"));
        WS = tmp.resolve("sandbox").resolve("ws").toAbsolutePath().normalize();
    }

    // ---- 正常路径 ----

    @Test
    void 相对路径落Workspace内() {
        assertThat(SafePath.resolveUserPath(WS, "sub/file.txt")).isEqualTo(WS.resolve("sub/file.txt"));
    }

    @Test
    void 空字符串返回Workspace本身() {
        assertThat(SafePath.resolveUserPath(WS, "")).isEqualTo(WS);
    }

    @Test
    void 点号返回Workspace本身() {
        assertThat(SafePath.resolveUserPath(WS, ".")).isEqualTo(WS);
    }

    @Test
    void 重复斜杠被规整() {
        assertThat(SafePath.resolveUserPath(WS, "sub//file")).isEqualTo(WS.resolve("sub/file"));
    }

    @Test
    void unicode文件名正常放行() {
        assertThat(SafePath.resolveUserPath(WS, "中文/文件.txt")).isEqualTo(WS.resolve("中文/文件.txt"));
    }

    @Test
    void 绝对路径去前导斜杠后落Workspace内() {
        assertThat(SafePath.resolveUserPath(WS, "/etc/passwd")).isEqualTo(WS.resolve("etc/passwd"));
    }

    @Test
    void 首尾空白被trim() {
        assertThat(SafePath.resolveUserPath(WS, "  sub/file  ")).isEqualTo(WS.resolve("sub/file"));
    }

    // ---- 越权拒绝 ----

    @Test
    void 上越被拒() {
        assertThatThrownBy(() -> SafePath.resolveUserPath(WS, "../"))
                .isInstanceOf(PathEscapeException.class);
    }

    @Test
    void 多层上越被拒() {
        assertThatThrownBy(() -> SafePath.resolveUserPath(WS, "../../etc"))
                .isInstanceOf(PathEscapeException.class);
    }

    @Test
    void 嵌套上越被拒() {
        assertThatThrownBy(() -> SafePath.resolveUserPath(WS, "sub/../../../etc"))
                .isInstanceOf(PathEscapeException.class);
    }

    @Test
    void 伪前缀攻击被拒() {
        // normalized = …/sandbox/ws-evil/x：字符串虽含 ws，但路径组件 ws 后面不是 ws 的子路径。
        assertThatThrownBy(() -> SafePath.resolveUserPath(WS, "../ws-evil/x"))
                .isInstanceOf(PathEscapeException.class);
    }

    @Test
    void 空字节被拒() {
        assertThatThrownBy(() -> SafePath.resolveUserPath(WS, "a\0b"))
                .isInstanceOf(PathEscapeException.class);
    }

    @Test
    void 空字节藏在绝对路径也被拒() {
        assertThatThrownBy(() -> SafePath.resolveUserPath(WS, "/etc\0/passwd"))
                .isInstanceOf(PathEscapeException.class);
    }

    // Windows 上反斜杠是分隔符，..\..\evil 会被 normalize 解析为上越，前缀校验应拦截；
    // posix 上反斜杠只是文件名字符，不构成穿越（与 TS 版行为一致，仅 win32 断言）。
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void windows反斜杠穿越被拦截() {
        assertThatThrownBy(() -> SafePath.resolveUserPath(WS, "..\\..\\evil"))
                .isInstanceOf(PathEscapeException.class);
    }

    // Java 版补充：Windows 盘符绝对路径（C:/x、C:\x）解析后逃出 workspace，同样被拒。
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void windows盘符绝对路径被拒() {
        assertThatThrownBy(() -> SafePath.resolveUserPath(WS, "C:/Windows/evil"))
                .isInstanceOf(PathEscapeException.class);
        assertThatThrownBy(() -> SafePath.resolveUserPath(WS, "C:\\Windows\\evil"))
                .isInstanceOf(PathEscapeException.class);
    }
}
