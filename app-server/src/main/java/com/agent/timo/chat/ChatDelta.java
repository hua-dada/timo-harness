package com.agent.timo.chat;

/**
 * M1-6 SSE 下行事件协议（ChatDelta）。逐条对齐源项目 {@code apps/web/src/lib/events.ts} 的
 * ChatDelta 判别联合——前端 {@code lib/sse.ts} 只需 {@code JSON.parse(data)} 后按 {@code type}
 * 分发，{@code store/chat-delta.ts} 的 reducer 语义零改动（spec §3.3）。
 *
 * <p>SSE 帧形如 {@code id:<seq>\nevent:<type>\ndata:{"type":"...",...}}：{@code type} 同时出现在
 * event 名与 data 里（自描述，前端按任一分发均可）。所有事件由 {@link SessionEventBuffer} 分配
 * 会话内单调 seq，{@code Last-Event-Id} 断线续传按 seq 补发。
 *
 * <p>M1-6 投影子集：源有而 AgentScope 2.0.2 无对应语义的事件（compaction_* / tree /
 * fork_messages / session_title / runtime_invalidated / switch_response / ready）不定义，
 * 待 M1-11/M2 引入对应能力时补充。messages_loaded 在 M1-6 恒为空数组（无持久化，重连状态
 * 依赖 seq 缓冲补发；M1-11 落 DB 后回真实历史）。
 */
public sealed interface ChatDelta {

    /** 判别字段，同时用作 SSE 的 {@code event:} 名（各 record 的首个组件天然实现之）。 */
    String type();

    /** 会话元信息（SSE 连接建立 / 全量重建时首发）。 */
    record SessionInfo(String type, String sessionId, String sessionName) implements ChatDelta {
        public static SessionInfo of(String sessionId, String sessionName) {
            return new SessionInfo("session_info", sessionId, sessionName);
        }
    }

    /**
     * 历史消息全量（仅全量重建时发）。载荷是源 pi {@code AgentMessage} 形状的数组
     * （{@code session_entries.payload} 原文，见 SessionEntryProjector 契约注释）；
     * M1-6 曾恒为空数组。
     */
    record MessagesLoaded(String type, java.util.List<Object> messages) implements ChatDelta {
        public static MessagesLoaded empty() {
            return new MessagesLoaded("messages_loaded", java.util.List.of());
        }

        public static MessagesLoaded of(java.util.List<Object> messages) {
            return new MessagesLoaded("messages_loaded", messages);
        }
    }

    /** 思考档位同步（off/low/medium/xhigh；实际生效档位，含切换确认回流）。 */
    record ThinkingLevel(String type, String level) implements ChatDelta {
        public static ThinkingLevel of(String level) {
            return new ThinkingLevel("thinking_level", level);
        }
    }

    /** prompt 已受理（对应源 WS 的 response{command:"prompt",success:true}）。 */
    record PromptAccepted(String type) implements ChatDelta {
        public static PromptAccepted of() {
            return new PromptAccepted("prompt_accepted");
        }
    }

    /** 开新思考块。 */
    record ThinkingStart(String type) implements ChatDelta {
        public static ThinkingStart of() {
            return new ThinkingStart("thinking_start");
        }
    }

    /** 思考文本增量。 */
    record ThinkingDelta(String type, String text) implements ChatDelta {
        public static ThinkingDelta of(String text) {
            return new ThinkingDelta("thinking_delta", text);
        }
    }

    /** 正文文本增量。 */
    record TextDelta(String type, String text) implements ChatDelta {
        public static TextDelta of(String text) {
            return new TextDelta("text_delta", text);
        }
    }

    /** 工具调用开始（提前建卡，覆盖「模型构造参数」的反馈空窗；按 id 与 tool_end 配对）。 */
    record ToolStart(String type, String id, String name, Object args) implements ChatDelta {
        public static ToolStart of(String id, String name, Object args) {
            return new ToolStart("tool_start", id, name, args);
        }
    }

    /** 工具调用完整参数回填（toolcall_end 语义）。 */
    record ToolArgs(String type, String id, Object args) implements ChatDelta {
        public static ToolArgs of(String id, Object args) {
            return new ToolArgs("tool_args", id, args);
        }
    }

    /** 工具执行结束。 */
    record ToolEnd(String type, String id, String name, boolean isError, Object result) implements ChatDelta {
        public static ToolEnd of(String id, String name, boolean isError, Object result) {
            return new ToolEnd("tool_end", id, name, isError, result);
        }
    }

    /** 回合终态用量（message_end 语义：total=上下文总 token，stopReason=length 提示截断）。 */
    record MessageUsage(String type, Long total, String stopReason) implements ChatDelta {
        public static MessageUsage of(Long total, String stopReason) {
            return new MessageUsage("message_usage", total, stopReason);
        }
    }

    /**
     * HITL 请求浮层（源 {@code extension_ui_request} 形状：uuid id + method，前端独立
     * ui-requests store 渲染）。M1-6 的 method 恒为 {@code confirm}（危险命令人工确认）。
     */
    record UiRequest(String type, UiRequestPayload request) implements ChatDelta {
        public static UiRequest of(UiRequestPayload request) {
            return new UiRequest("ui_request", request);
        }
    }

    /** 一轮 prompt 处理完成（关闭末尾思考块、置 done、退出 streaming）。 */
    record AgentEnd(String type) implements ChatDelta {
        public static AgentEnd of() {
            return new AgentEnd("agent_end");
        }
    }

    /** 错误（统一复位流式/加载态）。 */
    record Error(String type, String message) implements ChatDelta {
        public static Error of(String message) {
            return new Error("error", message);
        }
    }
}
