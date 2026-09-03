package com.apeloa.agent.chat;

/**
 * 会话概要（M1-11）：列表路由的行模型——DB 行贡献 id/name/createdAt，内存实例（若在）贡献
 * 实时 state。与 {@link AgentSession} 解耦后，列会话不再需要重建 Agent。
 */
public record SessionSummary(String sessionId, String name, String state, long createdAt) {
}
