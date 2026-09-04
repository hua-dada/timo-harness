package com.agent.timo.chat;

/**
 * 会话不存在、或不属于当前用户（两种情况回同一个 404，不泄露他人会话是否存在）。
 * M1-6 会话只在内存里：进程重启或空闲驱逐后也走这里，M1-11 落 DB 后可重建。
 */
public class SessionNotFoundException extends RuntimeException {

    public SessionNotFoundException(String sessionId) {
        super("会话不存在：" + sessionId);
    }
}
