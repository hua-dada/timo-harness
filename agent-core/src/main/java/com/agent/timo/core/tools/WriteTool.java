package com.agent.timo.core.tools;

import com.agent.timo.core.files.PathEscapeException;
import com.agent.timo.core.files.SafePath;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 写文件工具：整体写入（覆盖）。路径经 {@link SafePath} 限制在 workspace 内。
 *
 * <p>对应源项目 pi coding-tools 的 write 语义（M1-7）。
 */
public class WriteTool {

    protected final Path workspace;

    public WriteTool(Path workspace) {
        this.workspace = workspace.toAbsolutePath().normalize();
    }

    @Tool(name = "write_file",
            description = "把内容整体写入 workspace 内的文件（UTF-8，覆盖已存在文件；父目录自动创建）。"
                    + "单次 content 不要超过约 4000 字符：更大的文件必须分段写——第一次写入开头并在末尾放唯一"
                    + "结束标记（如 <!--APPEND-->），随后每次用 edit_file 把该标记替换为「下一段内容 + 标记」，"
                    + "最后一段去掉标记。超长单次写入会因生成截断而失败。",
            concurrencySafe = false)
    public String write(
            @ToolParam(name = "path", description = "相对 workspace 的文件路径") String path,
            @ToolParam(name = "content", description = "要写入的完整文件内容") String content) {
        try {
            Path file = SafePath.resolveUserPath(workspace, path);
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Files.writeString(file, content == null ? "" : content, StandardCharsets.UTF_8);
            return String.format("OK：已写入 %d 字节 → %s",
                    Files.size(file), workspace.relativize(file));
        } catch (PathEscapeException e) {
            return "错误：" + e.getMessage();
        } catch (IOException e) {
            return "错误：写入失败：" + e.getMessage();
        }
    }
}
