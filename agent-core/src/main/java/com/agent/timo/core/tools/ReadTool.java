package com.agent.timo.core.tools;

import com.agent.timo.core.files.PathEscapeException;
import com.agent.timo.core.files.SafePath;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 只读文件工具：读文本文件（带行号）+ 列目录。所有路径参数经 {@link SafePath} 限制在 workspace 内。
 *
 * <p>对应源项目 pi coding-tools 的 read/list 语义（M1-7）。
 */
public class ReadTool {

    /** 单次读取的文件大小上限：超限直接报错，防止把大文件灌进上下文。 */
    static final long MAX_FILE_BYTES = 10 * 1024 * 1024;

    /** list 目录条目上限。 */
    static final int MAX_LIST_ENTRIES = 500;

    protected final Path workspace;

    public ReadTool(Path workspace) {
        this.workspace = workspace.toAbsolutePath().normalize();
    }

    @Tool(name = "read_file",
            description = "读取 workspace 内的文本文件，返回 cat -n 风格的带行号内容。"
                    + "可用 offset/limit 分段读取大文件。",
            readOnly = true,
            concurrencySafe = true)
    public String read(
            @ToolParam(name = "path", description = "相对 workspace 的文件路径") String path,
            @ToolParam(name = "offset", required = false, description = "起始行号（1 起，含）") Integer offset,
            @ToolParam(name = "limit", required = false, description = "最多返回的行数") Integer limit) {
        int from = (offset == null || offset < 1) ? 1 : offset;
        int max = (limit == null || limit < 1) ? 2000 : limit;
        try {
            Path file = SafePath.resolveUserPath(workspace, path);
            if (!Files.isRegularFile(file)) {
                return "错误：文件不存在：" + display(file);
            }
            if (Files.size(file) > MAX_FILE_BYTES) {
                return "错误：文件超过 " + (MAX_FILE_BYTES / 1024 / 1024) + "MB 上限，请用 offset/limit 读取片段";
            }
            byte[] bytes = Files.readAllBytes(file);
            for (byte b : bytes) {
                if (b == 0) {
                    return "错误：疑似二进制文件，拒绝读取：" + display(file);
                }
            }
            String[] lines = new String(bytes, StandardCharsets.UTF_8).split("\n", -1);
            StringBuilder out = new StringBuilder();
            int to = Math.min(from + max - 1, lines.length);
            for (int i = from; i <= to; i++) {
                out.append(String.format("%6d\t%s%n", i, lines[i - 1]));
            }
            if (to < lines.length) {
                out.append(String.format("…（共 %d 行，已显示 %d–%d 行；继续读请用 offset=%d）%n",
                        lines.length, from, to, to + 1));
            }
            return out.toString();
        } catch (PathEscapeException e) {
            return "错误：" + e.getMessage();
        } catch (IOException e) {
            return "错误：读取失败：" + e.getMessage();
        }
    }

    @Tool(name = "list_dir",
            description = "列出 workspace 内目录的内容：目录在前（带 / 后缀），文件带字节数。",
            readOnly = true,
            concurrencySafe = true)
    public String list(
            @ToolParam(name = "path", required = false, description = "相对 workspace 的目录路径，缺省为根") String path) {
        try {
            Path dir = SafePath.resolveUserPath(workspace, path == null ? "" : path);
            if (!Files.isDirectory(dir)) {
                return "错误：目录不存在：" + display(dir);
            }
            List<Path> entries;
            try (var stream = Files.list(dir)) {
                entries = new ArrayList<>(stream.sorted(Comparator
                                .comparing((Path p) -> !Files.isDirectory(p))
                                .thenComparing(p -> p.getFileName().toString().toLowerCase()))
                        .limit(MAX_LIST_ENTRIES + 1L)
                        .toList());
            }
            StringBuilder out = new StringBuilder();
            for (Path p : entries) {
                if (Files.isDirectory(p)) {
                    out.append(String.format("%s/%n", p.getFileName()));
                } else {
                    out.append(String.format("%s (%d bytes)%n", p.getFileName(), Files.size(p)));
                }
            }
            if (entries.size() > MAX_LIST_ENTRIES) {
                out.append(String.format("…（条目超过 %d，已截断）%n", MAX_LIST_ENTRIES));
            }
            return out.isEmpty() ? "（空目录）" : out.toString();
        } catch (PathEscapeException e) {
            return "错误：" + e.getMessage();
        } catch (IOException e) {
            return "错误：列目录失败：" + e.getMessage();
        }
    }

    /** 展示用相对路径（workspace 内），避免把宿主绝对路径泄露给模型。 */
    protected final String display(Path file) {
        return workspace.relativize(file).toString();
    }
}
