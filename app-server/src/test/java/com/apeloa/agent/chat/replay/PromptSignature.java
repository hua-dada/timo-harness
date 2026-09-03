package com.apeloa.agent.chat.replay;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * prompt 的可读规范形式：把 {@code List<Msg>} 压成逐条逐块的字符串，供等值断言。
 *
 * <p>直接 assert {@code List<Msg>} 不可行——{@code Msg} 带随机 id/时间戳，equals 必然不等；
 * 而只比文本又会漏掉工具调用参数、工具结果状态这些真正决定模型行为的部分。这里保留
 * 「角色 + 块类型 + 语义载荷」，丢掉 id/时间戳等每次必变的运行时字段，工具参数按键排序
 * （Map 序列化顺序不稳定，不排序会假失败）。
 *
 * <p>工具调用 id 也要丢：重放走的是同一份文档复制，id 天然相同；但截断重放后模型会生成新
 * id，比较时不该因此判不一致——判据是「同样的调用、同样的参数、同样的结果」。
 */
final class PromptSignature {

    private PromptSignature() {}

    static List<String> of(List<Msg> messages) {
        List<String> out = new ArrayList<>();
        for (Msg msg : messages) {
            String role = msg.getRole() == null ? "?" : msg.getRole().name().toLowerCase();
            List<String> blocks = new ArrayList<>();
            if (msg.getContent() != null) {
                for (ContentBlock block : msg.getContent()) {
                    blocks.add(describe(block));
                }
            }
            out.add(role + "[" + String.join(",", blocks) + "]");
        }
        return out;
    }

    private static String describe(ContentBlock block) {
        if (block instanceof TextBlock text) {
            return "text:" + text.getText();
        }
        if (block instanceof ThinkingBlock thinking) {
            return "thinking:" + thinking.getThinking();
        }
        if (block instanceof ToolUseBlock tool) {
            return "toolCall:" + tool.getName() + args(tool.getInput());
        }
        if (block instanceof ToolResultBlock result) {
            return "toolResult:"
                    + result.getName()
                    + ":"
                    + (result.getState() == null ? "?" : result.getState().name())
                    + ":"
                    + textOf(result.getOutput());
        }
        return block.getClass().getSimpleName();
    }

    private static String args(Map<String, Object> input) {
        if (input == null || input.isEmpty()) {
            return "{}";
        }
        return new TreeMap<>(input)
                .entrySet().stream()
                        .map(e -> e.getKey() + "=" + e.getValue())
                        .collect(Collectors.joining(",", "{", "}"));
    }

    private static String textOf(List<ContentBlock> blocks) {
        if (blocks == null) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (ContentBlock block : blocks) {
            if (block instanceof TextBlock t && t.getText() != null) {
                text.append(t.getText());
            }
        }
        return text.toString();
    }
}
