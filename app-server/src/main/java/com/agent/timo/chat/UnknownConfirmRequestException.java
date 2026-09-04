package com.agent.timo.chat;

/**
 * HITL 应答的 requestId 不在待确认表里（已应答 / 已过期 / 会话已重启）。web 映射 404。
 */
public class UnknownConfirmRequestException extends RuntimeException {

    public UnknownConfirmRequestException(String requestId) {
        super("确认请求不存在或已处理：" + requestId);
    }
}
