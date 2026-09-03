package com.apeloa.agent.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.AllToolsDeniedEvent;
import io.agentscope.core.event.ExceedMaxItersEvent;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockStartEvent;
import io.agentscope.core.event.ToolCallDeltaEvent;
import io.agentscope.core.event.ToolCallEndEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultStartEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatUsage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * AgentScope 类型化事件 → {@link ChatDelta} 投影（M1-6 SSE 网关桥核心）。
 *
 * <p><b>每次 run 一个实例</b>（不可复用、非线程安全）：AgentScope 2.0.2 的工具事件不带载荷——
 * {@code ToolCallStart/EndEvent} 无 args、{@code ToolResultEndEvent} 无 result，参数与结果都只
 * 经 {@code ToolCallDeltaEvent}/{@code ToolResultTextDeltaEvent} 的增量片段下发。故本类必须按
 * toolCallId 累积片段，在 End 事件处一次性产出源协议的 {@code tool_args}/{@code tool_end}
 * （计划书原写「纯函数」，实测事件形状后改为按 run 持有累积器）。
 *
 * <p><b>HITL 暂停语义</b>：权限 ASK 命中时框架发 {@link RequireUserConfirmEvent} 并让本次
 * streamEvents 正常收尾（RequestStop + AgentResult(PERMISSION_ASKING) + AgentEnd）。此时
 * <b>吞掉 agent_end</b>——前端保持 streaming、assistant 消息不置 done，等应答后的续跑事件继续
 * 拼进同一条消息（工具卡按 id 配对才能在续跑里收到 tool_end）。待确认的 ToolUseBlock 经
 * {@link #confirmToolCalls()} 暴露给 {@code AgentSession} 回填 {@code ConfirmResult}。
 *
 * <p>不投影：Text/ThinkingBlockEnd、DataBlock*、ModelCall*、RequestStop（权限暂停也发它，不能
 * 当回合结束）、ToolResultDataDelta（当前工具均返文本）、Subagent/Hint/Custom（M2 再议）。
 */
public final class AgentEventMapper {

    private static final Logger log = LoggerFactory.getLogger(AgentEventMapper.class);

    private static final ObjectMapper JSON = new ObjectMapper();

    /** toolCallId → 模型流式下发的入参 JSON 片段。 */
    private final Map<String, StringBuilder> argFragments = new HashMap<>();

    /** toolCallId → 工具结果文本片段。 */
    private final Map<String, StringBuilder> resultFragments = new HashMap<>();

    /** 本 run 待人工确认的工具调用（应答后由 AgentSession 清空）。 */
    private final List<ToolUseBlock> confirmToolCalls = new ArrayList<>();

    private String confirmReplyId;

    /** 单条事件 → 0..n 条 ChatDelta（不产出即返空 List）。 */
    public List<ChatDelta> map(AgentEvent event) {
        return switch (event) {
            case ThinkingBlockStartEvent e -> List.of(ChatDelta.ThinkingStart.of());
            case ThinkingBlockDeltaEvent e ->
                    isBlank(e.getDelta())
                            ? List.of()
                            : List.of(ChatDelta.ThinkingDelta.of(e.getDelta()));
            case TextBlockDeltaEvent e ->
                    isBlank(e.getDelta())
                            ? List.of()
                            : List.of(ChatDelta.TextDelta.of(e.getDelta()));
            case ToolCallStartEvent e -> {
                // 建卡先行（args 未知，随后由 tool_args 回填），覆盖模型构造入参的反馈空窗。
                argFragments.put(e.getToolCallId(), new StringBuilder());
                yield List.of(
                        ChatDelta.ToolStart.of(e.getToolCallId(), e.getToolCallName(), null));
            }
            case ToolCallDeltaEvent e -> {
                argFragments
                        .computeIfAbsent(e.getToolCallId(), k -> new StringBuilder())
                        .append(e.getDelta() == null ? "" : e.getDelta());
                yield List.of();
            }
            case ToolCallEndEvent e ->
                    List.of(
                            ChatDelta.ToolArgs.of(
                                    e.getToolCallId(),
                                    parseArgs(argFragments.remove(e.getToolCallId()))));
            case ToolResultStartEvent e -> {
                resultFragments.put(e.getToolCallId(), new StringBuilder());
                yield List.of();
            }
            case ToolResultTextDeltaEvent e -> {
                resultFragments
                        .computeIfAbsent(e.getToolCallId(), k -> new StringBuilder())
                        .append(e.getDelta() == null ? "" : e.getDelta());
                yield List.of();
            }
            case ToolResultEndEvent e -> List.of(toolEnd(e));
            case RequireUserConfirmEvent e -> confirmRequests(e);
            case ExceedMaxItersEvent e ->
                    List.of(ChatDelta.Error.of("已达最大迭代轮数 " + e.getMaxIters() + "，本轮中止"));
            case AllToolsDeniedEvent e ->
                    List.of(
                            ChatDelta.Error.of(
                                    "工具调用全部被拒绝：" + toolNames(e.getDeniedToolCalls())));
            case AgentResultEvent e -> result(e.getResult());
            // 权限暂停时吞掉：本轮未结束，等应答续跑（见类注释）。
            case AgentEndEvent e ->
                    awaitingConfirm() ? List.of() : List.of(ChatDelta.AgentEnd.of());
            default -> {
                log.trace("未投影事件：{}", event.getType());
                yield List.of();
            }
        };
    }

    /** 本 run 是否停在人工确认上（决定是否吞 agent_end、会话是否转 AWAITING_CONFIRM）。 */
    public boolean awaitingConfirm() {
        return !confirmToolCalls.isEmpty();
    }

    /** 待确认的工具调用（顺序与 {@code ui_request} 发出顺序一致）。 */
    public List<ToolUseBlock> confirmToolCalls() {
        return List.copyOf(confirmToolCalls);
    }

    /** 暂停时的 replyId（仅日志/诊断用；框架自己从 Msg 元数据里找回它做事件关联）。 */
    public String confirmReplyId() {
        return confirmReplyId;
    }

    /**
     * 每个待确认工具调用发一条 {@code ui_request}：{@code id} 取 toolCallId（天然唯一且与工具卡
     * 同键），前端浮层应答后 {@code POST /api/hitl/{id}} 即可精确定位到对应 ToolUseBlock。
     */
    private List<ChatDelta> confirmRequests(RequireUserConfirmEvent event) {
        confirmReplyId = event.getReplyId();
        List<ChatDelta> deltas = new ArrayList<>();
        for (ToolUseBlock call : event.getToolCalls()) {
            confirmToolCalls.add(call);
            deltas.add(
                    ChatDelta.UiRequest.of(
                            UiRequestPayload.confirm(
                                    call.getId(), "确认执行 " + call.getName() + summarize(call))));
        }
        return deltas;
    }

    private ChatDelta toolEnd(ToolResultEndEvent event) {
        ToolResultState state = event.getState();
        boolean isError = state != null && state != ToolResultState.SUCCESS;
        StringBuilder acc = resultFragments.remove(event.getToolCallId());
        String text = acc == null ? "" : acc.toString();
        // DENIED/INTERRUPTED 由框架直接写结果块、不走文本增量，累积器为空时按状态补一句可读结论。
        Object result = !text.isEmpty() ? text : stateText(state);
        return ChatDelta.ToolEnd.of(event.getToolCallId(), event.getToolCallName(), isError, result);
    }

    /**
     * 终态消息 → {@code message_usage}（+ 挂起工具的显式报错）。用量取 {@code totalTokens}
     * （AgentScope 口径 = input+output，不含 cached；比源前端的 pi 口径略低估上下文占用），
     * 0/缺失不发以免把 undefined 当 0。
     */
    private List<ChatDelta> result(Msg msg) {
        if (msg == null) {
            return List.of();
        }
        ChatUsage usage = msg.getChatUsage();
        Long total =
                usage != null && usage.getTotalTokens() > 0 ? (long) usage.getTotalTokens() : null;
        GenerateReason reason = msg.getGenerateReason();
        String stopReason = stopReasonOf(reason);
        List<ChatDelta> deltas = new ArrayList<>();
        if (total != null || stopReason != null) {
            deltas.add(ChatDelta.MessageUsage.of(total, stopReason));
        }
        if (reason == GenerateReason.TOOL_SUSPENDED) {
            // BashTool 危险命令走 ToolSuspendException（外部执行协议），不发
            // RequireUserConfirmEvent，M1-6 没有对应应答通道：显式报错而非静默收尾。
            // M1-8 把 bash ASK 迁到 PermissionRule 后即并入 ui_request 链路。
            deltas.add(ChatDelta.Error.of("工具已挂起等待人工执行，当前版本尚未接通该应答通道（M1-8）"));
        }
        return deltas;
    }

    /** 仅映射非正常收尾的终因（前端只渲染 length，其余仅落 stopReason 供诊断）。 */
    private static String stopReasonOf(GenerateReason reason) {
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

    private static String stateText(ToolResultState state) {
        if (state == null) {
            return null;
        }
        return switch (state) {
            case DENIED -> "用户已拒绝执行";
            case INTERRUPTED -> "已中止";
            case ERROR -> "工具执行失败";
            case SUCCESS, RUNNING -> null;
        };
    }

    /** 片段 → args 对象；解析失败退化为原始串（宁可让前端展示原文，也不丢参数）。 */
    private static Object parseArgs(StringBuilder acc) {
        if (acc == null) {
            return null;
        }
        String raw = acc.toString().trim();
        if (raw.isEmpty()) {
            return null;
        }
        try {
            return JSON.readValue(raw, Object.class);
        } catch (Exception e) {
            log.debug("工具入参非法 JSON，按原文下发：{}", raw);
            return raw;
        }
    }

    /** confirm 标题补一段入参摘要（超长截断），让用户看清确认的到底是哪条命令。 */
    private static String summarize(ToolUseBlock call) {
        Map<String, Object> input = call.getInput();
        if (input == null || input.isEmpty()) {
            return "";
        }
        String text = String.valueOf(input.values().iterator().next());
        return "：" + (text.length() > 120 ? text.substring(0, 120) + "…" : text);
    }

    private static String toolNames(List<ToolUseBlock> calls) {
        StringJoiner joiner = new StringJoiner("、");
        for (ToolUseBlock call : calls) {
            joiner.add(call.getName());
        }
        return joiner.toString();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isEmpty();
    }
}
