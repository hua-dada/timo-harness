package com.apeloa.agent.chat;

import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import java.util.List;
import reactor.core.publisher.Flux;

/**
 * 会话侧 Agent 抽象：把 {@code ReActAgent} + 该会话的 {@code RuntimeContext} 收成一个窄接口，
 * 让 {@link AgentSession} 只依赖「发一条消息 → 拿事件流」与「中止」两件事。
 *
 * <p>存在这层的唯一理由是可测：{@code ReActAgent} 是终态类且必须有真实 Model 才能构造，
 * 集成测试用脚本化 stub 实现本接口即可完全离线跑通 SSE / HITL 链路。
 */
public interface ChatAgent {

    /**
     * 发起一次 run。{@code message} 既是普通用户 prompt，也可是携带
     * {@code Msg.METADATA_CONFIRM_RESULTS} 的 HITL 应答载体（续跑同一回合）。
     */
    Flux<AgentEvent> stream(Msg message);

    /** 中止本会话在途 run（框架按 userId+sessionId 定位，不影响其他会话）。 */
    void interrupt();

    /**
     * 当前已提交进框架上下文的消息快照（M1-11 entries 落盘的权威源）。在 run 收尾时被
     * {@code AgentSession} 读取做增量投影；默认空（stub / 无状态实现），生产实现返回
     * {@code ReActAgent.getAgentState(...).getContext()} 的防御性副本。
     */
    default List<Msg> committedContext() {
        return List.of();
    }
}
