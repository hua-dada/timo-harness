package com.agent.timo.chat;

/**
 * 每会话一个 {@link ChatAgent}：AgentScope 的对话记忆存在按 (userId, sessionId) 索引的
 * AgentState 里，故 Agent 实例本身可复用，但工具需绑定该用户的 workspace，仍按会话构造。
 *
 * <p>无可用 Model bean（未配 new-api）时抛 {@link ModelNotConfiguredException}，web 层映射 503。
 */
public interface ChatAgentFactory {

    ChatAgent create(String userId, String sessionId);
}
