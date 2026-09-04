package com.agent.timo.web.chat;

/**
 * 聊天路由的请求体不合法（message 空、HITL action 不认识、modify 没带 args）。web 映射 400。
 *
 * <p>不用 Bean Validation：这几处校验都要带具体中文文案回给前端浮层，走异常比拼装
 * {@code MethodArgumentNotValidException} 的字段错误更直白。
 */
public class BadChatRequestException extends RuntimeException {

    public BadChatRequestException(String message) {
        super(message);
    }
}
