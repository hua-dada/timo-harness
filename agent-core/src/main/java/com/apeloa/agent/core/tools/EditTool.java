package com.apeloa.agent.core.tools;

import com.apeloa.agent.core.files.PathEscapeException;
import com.apeloa.agent.core.files.SafePath;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 精确字符串替换编辑工具（对齐 Claude Code Edit 语义）：
 * old_string 必须在文件中恰好出现一次，除非 replace_all=true；替换后整体写回。
 *
 * <p>对应源项目 pi coding-tools 的 edit 语义（M1-7）。
 */
public class EditTool {

    protected final Path workspace;

    public EditTool(Path workspace) {
        this.workspace = workspace.toAbsolutePath().normalize();
    }

    @Tool(name = "edit_file",
            description = "对 workspace 内文件做精确字符串替换。"
                    + "old_string 必须唯一命中（含缩进/空白），多处命中需 replace_all=true 或补充上下文使其唯一。",
            concurrencySafe = false)
    public String edit(
            @ToolParam(name = "path", description = "相对 workspace 的文件路径") String path,
            @ToolParam(name = "old_string", description = "要替换的原文（精确匹配，含空白）") String oldString,
            @ToolParam(name = "new_string", description = "替换后的新文本") String newString,
            @ToolParam(name = "replace_all", required = false, description = "true 时替换全部命中；默认 false") Boolean replaceAll) {
        boolean all = Boolean.TRUE.equals(replaceAll);
        if (oldString == null || oldString.isEmpty()) {
            return "错误：old_string 不能为空";
        }
        if (oldString.equals(newString)) {
            return "错误：old_string 与 new_string 相同，无需替换";
        }
        try {
            Path file = SafePath.resolveUserPath(workspace, path);
            if (!Files.isRegularFile(file)) {
                return "错误：文件不存在：" + workspace.relativize(file);
            }
            String content = Files.readString(file, StandardCharsets.UTF_8);
            int first = content.indexOf(oldString);
            if (first < 0) {
                return "错误：old_string 未在文件中命中。请检查是否与文件内容（含缩进）完全一致";
            }
            int count = 1;
            for (int idx = content.indexOf(oldString, first + 1); idx >= 0;
                    idx = content.indexOf(oldString, idx + 1)) {
                count++;
            }
            if (count > 1 && !all) {
                return String.format("错误：old_string 命中 %d 处。请补充上下文使其唯一，或设 replace_all=true", count);
            }
            String updated = all
                    ? content.replace(oldString, newString == null ? "" : newString)
                    : content.substring(0, first) + (newString == null ? "" : newString)
                            + content.substring(first + oldString.length());
            Files.writeString(file, updated, StandardCharsets.UTF_8);
            return String.format("OK：替换 %d 处 → %s", all ? count : 1, workspace.relativize(file));
        } catch (PathEscapeException e) {
            return "错误：" + e.getMessage();
        } catch (IOException e) {
            return "错误：编辑失败：" + e.getMessage();
        }
    }
}
