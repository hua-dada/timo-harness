package com.apeloa.agent.chat;

/**
 * 会话仍在处理上一条消息（RUNNING）或在等人工确认（AWAITING_CONFIRM）时又收到 prompt。
 *
 * <p>源项目由 pi 子进程串行化保证「一次一条」，Java 版在会话层显式拒绝：web 映射 409
 * {@code {"error":"上一条消息仍在处理"}}，而不是让框架抛 IllegalStateException。
 */
public class SessionBusyException extends RuntimeException {

    public SessionBusyException(AgentSession.State state) {
        super(state == AgentSession.State.AWAITING_CONFIRM
                ? "上一条消息仍在等待人工确认"
                : "上一条消息仍在处理");
    }
}
