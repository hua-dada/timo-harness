package com.apeloa.agent.core.tools;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** coding 文件工具行为测试（M1-7）：正常读写编 + 越权一律返回错误文本（不抛异常，模型可见）。 */
class CodingFileToolsTest {

    @TempDir
    Path ws;

    private ReadTool read;
    private WriteTool write;
    private EditTool edit;

    @BeforeEach
    void setUp() {
        read = new ReadTool(ws);
        write = new WriteTool(ws);
        edit = new EditTool(ws);
    }

    // ---- Write → Read 回环 ----

    @Test
    void 写入并读回带行号内容() {
        String result = write.write("src/A.java", "public class A {\n}\n");
        assertThat(result).startsWith("OK");

        String content = read.read("src/A.java", null, null);
        assertThat(content).contains("     1\tpublic class A {");
        assertThat(content).contains("     2\t}");
    }

    @Test
    void 写入返回字节数且父目录自动创建() throws IOException {
        write.write("a/b/c.txt", "hello");
        assertThat(Files.readString(ws.resolve("a/b/c.txt"))).isEqualTo("hello");
    }

    @Test
    void 覆盖写入以最后一次为准() {
        write.write("f.txt", "old");
        write.write("f.txt", "new");
        assertThat(read.read("f.txt", null, null)).contains("new");
    }

    // ---- Read 分段/防御 ----

    @Test
    void offset与limit分段读取并提示续读位置() {
        write.write("lines.txt", "l1\nl2\nl3\nl4\nl5\n");
        String out = read.read("lines.txt", 2, 2);
        assertThat(out).contains("l2").contains("l3").doesNotContain("l1").doesNotContain("l4");
        assertThat(out).contains("offset=4");
    }

    @Test
    void 文件不存在报错() {
        assertThat(read.read("nope.txt", null, null)).startsWith("错误：文件不存在");
    }

    @Test
    void 二进制文件拒绝读取() throws IOException {
        Files.write(ws.resolve("bin.dat"), new byte[]{1, 2, 0, 3});
        assertThat(read.read("bin.dat", null, null)).contains("二进制");
    }

    @Test
    void 读路径越权返回错误文本() {
        assertThat(read.read("../outside.txt", null, null)).contains("路径越权");
    }

    // ---- list ----

    @Test
    void 列目录目录在前带斜杠() throws IOException {
        write.write("zz.txt", "x");
        Files.createDirectories(ws.resolve("sub"));
        String out = read.list(null);
        int subIdx = out.indexOf("sub/");
        int fileIdx = out.indexOf("zz.txt (");
        assertThat(subIdx).isLessThan(fileIdx);
        assertThat(out).contains("zz.txt (1 bytes)");
    }

    @Test
    void 列空目录() throws IOException {
        Files.createDirectories(ws.resolve("empty"));
        assertThat(read.list("empty")).isEqualTo("（空目录）");
    }

    // ---- Edit ----

    @Test
    void 唯一命中精确替换() {
        write.write("code.txt", "alpha\nbeta\ngamma\n");
        String result = edit.edit("code.txt", "beta", "BETA", false);
        assertThat(result).startsWith("OK：替换 1 处");
        assertThat(read.read("code.txt", null, null)).contains("BETA").doesNotContain("beta");
    }

    @Test
    void 多处命中未开replaceAll报错并给出计数() {
        write.write("code.txt", "x\nx\nx\n");
        String result = edit.edit("code.txt", "x", "y", false);
        assertThat(result).contains("命中 3 处").contains("replace_all");
        assertThat(read.read("code.txt", null, null)).contains("x").doesNotContain("y");
    }

    @Test
    void replaceAll全部替换() {
        write.write("code.txt", "x\nx\nx\n");
        assertThat(edit.edit("code.txt", "x", "y", true)).startsWith("OK：替换 3 处");
        assertThat(read.read("code.txt", null, null)).doesNotContain("x");
    }

    @Test
    void 未命中报错() {
        write.write("code.txt", "content\n");
        assertThat(edit.edit("code.txt", "nope", "y", false)).contains("未在文件中命中");
    }

    @Test
    void 新旧相同报错() {
        write.write("code.txt", "content\n");
        assertThat(edit.edit("code.txt", "c", "c", false)).contains("相同");
    }

    @Test
    void 编辑路径越权返回错误文本() {
        assertThat(edit.edit("../etc/x", "a", "b", false)).contains("路径越权");
    }

    @Test
    void 写路径越权返回错误文本() {
        assertThat(write.write("../evil.txt", "x")).contains("路径越权");
    }
}
