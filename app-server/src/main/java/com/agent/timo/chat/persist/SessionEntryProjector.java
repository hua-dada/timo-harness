package com.agent.timo.chat.persist;

import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.Source;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.URLSource;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.model.ChatUsage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 框架 {@code Msg} → 前端历史消息（pi {@code AgentMessage} 形状）的投影（M1-11）。
 *
 * <p>线上契约以源前端 {@code lib/messages-history.ts} 的收窄逻辑为准（源 server 对历史是
 * {@code unknown[]} 透传，没有自己的 schema）：role ∈ user / assistant / toolResult /
 * compactionSummary；assistant content 是块数组 {@code {type:"text",text}} /
 * {@code {type:"thinking",thinking}} / {@code {type:"toolCall",id,name,arguments}} /
 * {@code {type:"image",data,mimeType}}；工具结果是独立一条消息
 * {@code {role:"toolResult",toolCallId,isError,content,details}}；用量为扁平
 * {@code usage.totalTokens}；另有顶层 {@code stopReason}。字段名易踩坑：thinking 不是 text、
 * arguments 不是 args。
 *
 * <p>一条 Msg 可投影多行（TOOL Msg 含多个 ToolResultBlock 时一转多）；SYSTEM / 合成消息
 * （metadata 带 {@code agentscope_synthetic}）不投影——对齐框架语义：合成消息不进持久化上下文。
 * 纯函数、无状态，单测直接构造 Msg 断言。
 */
public final class SessionEntryProjector {

    /** 源 server toTitle：标题截断长度。 */
    public static final int TITLE_MAX = 40;

    private SessionEntryProjector() {
    }

    /** 投影一条 Msg；无对应前端形状（system / 合成 / 空内容）返回空列表。 */
    public static List<Map<String, Object>> project(Msg msg) {
        if (msg == null || isSynthetic(msg)) {
            return List.of();
        }
        MsgRole role = msg.getRole();
        if (role == null) {
            return List.of();
        }
        return switch (role) {
            case USER -> List.of(userMessage(msg));
            case ASSISTANT -> assistantMessages(msg);
            case TOOL -> toolResultMessages(msg);
            case SYSTEM -> List.of();
        };
    }

    /** 首条用户消息 → 会话标题（源 {@code toTitle}：剥 markdown 记号、空白归一、40 字截断加省略号）。 */
    public static String titleOf(Msg userMsg) {
        String raw = userMsg == null ? null : textOf(userMsg);
        if (raw == null) {
            return null;
        }
        String normalized = raw.replaceAll("[`*_#>\\[\\](){}]", "").replaceAll("\\s+", " ").strip();
        return normalized.isEmpty()
                ? null
                : normalized.length() > TITLE_MAX
                        ? normalized.substring(0, TITLE_MAX) + "..."
                        : normalized;
    }

    private static Map<String, Object> userMessage(Msg msg) {
        Map<String, Object> am = new LinkedHashMap<>();
        am.put("role", "user");
        // 纯文本消息落字符串（前端 textOf 直接取）；带图片必须保留块数组——前端 imagesOf
        // 从 content 块里恢复图片，压成字符串会静默丢图。
        if (msg.getContent() != null
                && msg.getContent().stream().anyMatch(SessionEntryProjector::isProjectableImage)) {
            List<Object> blocks = new ArrayList<>();
            for (var block : msg.getContent()) {
                if (block instanceof TextBlock text && text.getText() != null) {
                    blocks.add(mapOf("type", "text", "text", text.getText()));
                } else if (block instanceof ImageBlock image) {
                    Map<String, Object> b = imageBlock(image);
                    if (b != null) {
                        blocks.add(b);
                    }
                }
            }
            am.put("content", blocks);
        } else {
            am.put("content", textOf(msg) == null ? "" : textOf(msg));
        }
        return am;
    }

    private static boolean isProjectableImage(io.agentscope.core.message.ContentBlock block) {
        return block instanceof ImageBlock image && imageBlock(image) != null;
    }

    /**
     * assistant：content 块数组 + 扁平 usage/stopReason（前端按 user 边界把同回合多条
     * assistant 合并展示，这里 1 Msg = 1 行，合并交给前端）。
     */
    private static List<Map<String, Object>> assistantMessages(Msg msg) {
        List<Map<String, Object>> blocks = new ArrayList<>();
        for (var block : msg.getContent()) {
            if (block instanceof TextBlock text && text.getText() != null) {
                blocks.add(mapOf("type", "text", "text", text.getText()));
            } else if (block instanceof ThinkingBlock thinking && thinking.getThinking() != null) {
                blocks.add(
                        mapOf("type", "thinking", "thinking", thinking.getThinking()));
            } else if (block instanceof ToolUseBlock tool) {
                Map<String, Object> b = new LinkedHashMap<>();
                b.put("type", "toolCall");
                b.put("id", tool.getId());
                b.put("name", tool.getName());
                b.put("arguments", tool.getInput() == null ? Map.of() : tool.getInput());
                blocks.add(b);
            } else if (block instanceof ImageBlock image && image.getSource() != null) {
                Map<String, Object> b = imageBlock(image);
                if (b != null) {
                    blocks.add(b);
                }
            }
            // 其余块（hint/data/audio/video）前端历史路径不消费，跳过。
        }
        Map<String, Object> am = new LinkedHashMap<>();
        am.put("role", "assistant");
        am.put("content", blocks);
        ChatUsage usage = msg.getChatUsage();
        if (usage != null && usage.getTotalTokens() > 0) {
            am.put("usage", mapOf("totalTokens", usage.getTotalTokens()));
        }
        String stopReason = stopReasonOf(msg);
        if (stopReason != null) {
            am.put("stopReason", stopReason);
        }
        return List.of(am);
    }

    /** TOOL Msg：每个 ToolResultBlock 一条独立 toolResult 消息（前端按 toolCallId 配对回填）。 */
    private static List<Map<String, Object>> toolResultMessages(Msg msg) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (var block : msg.getContent()) {
            if (!(block instanceof ToolResultBlock tool)) {
                continue;
            }
            Map<String, Object> am = new LinkedHashMap<>();
            am.put("role", "toolResult");
            am.put("toolCallId", tool.getId());
            // isError 口径与实时 tool_end 一致（AgentEventMapper.toolEnd）：非 SUCCESS 即错。
            ToolResultState state = tool.getState();
            am.put(
                    "isError",
                    state != null && state != ToolResultState.SUCCESS && state != ToolResultState.RUNNING);
            am.put("content", resultText(tool));
            // details（扩展快照，如 todo tasks）：AgentScope 无对应物，留空占位。
            am.put("details", null);
            result.add(am);
        }
        return result;
    }

    /** 前端 image 块契约：{type:"image", data:string, mimeType:"image/*"}；URL 源无 data 不落。 */
    private static Map<String, Object> imageBlock(ImageBlock image) {
        Source source = image.getSource();
        if (source instanceof Base64Source base64 && base64.getData() != null) {
            return mapOf(
                    "type", "image",
                    "data", base64.getData(),
                    "mimeType", base64.getMediaType() == null ? "image/png" : base64.getMediaType());
        }
        if (source instanceof URLSource url && url.getUrl() != null) {
            return mapOf(
                    "type", "image",
                    "data", url.getUrl(),
                    "mimeType", url.getMimeType() == null ? "image/png" : url.getMimeType());
        }
        return null;
    }

    private static String resultText(ToolResultBlock tool) {
        if (tool.getOutput() == null) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (var block : tool.getOutput()) {
            if (block instanceof TextBlock t && t.getText() != null) {
                text.append(t.getText());
            }
        }
        return text.toString();
    }

    /** 终态 stopReason 与实时 message_usage 同口径（见 AgentEventMapper.stopReasonOf）。 */
    private static String stopReasonOf(Msg msg) {
        io.agentscope.core.message.GenerateReason reason = msg.getGenerateReason();
        if (reason == null) {
            return null;
        }
        return switch (reason) {
            case TOOL_SUSPENDED -> "tool_suspended";
            case PERMISSION_ASKING -> "permission_asking";
            case ALL_TOOLS_DENIED -> "all_tools_denied";
            case INTERRUPTED -> "interrupted";
            case MAX_ITERATIONS -> "max_iterations";
            case REASONING_STOP_REQUESTED, ACTING_STOP_REQUESTED, MIDDLEWARE_STOP_REQUESTED ->
                    "stop_requested";
            case MODEL_STOP, TOOL_CALLS, STRUCTURED_OUTPUT -> null;
        };
    }

    private static boolean isSynthetic(Msg msg) {
        Object flag = msg.getMetadata() == null ? null : msg.getMetadata().get(Msg.METADATA_SYNTHETIC);
        return Boolean.TRUE.equals(flag);
    }

    private static String textOf(Msg msg) {
        if (msg.getContent() == null) {
            return null;
        }
        StringBuilder text = new StringBuilder();
        for (var block : msg.getContent()) {
            if (block instanceof TextBlock t && t.getText() != null) {
                text.append(t.getText());
            }
        }
        return text.isEmpty() ? null : text.toString();
    }

    private static Map<String, Object> mapOf(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], pairs[i + 1]);
        }
        return map;
    }
}
