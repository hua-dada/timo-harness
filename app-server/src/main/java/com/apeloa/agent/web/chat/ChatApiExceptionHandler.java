package com.apeloa.agent.web.chat;

import com.apeloa.agent.chat.ModelNotConfiguredException;
import com.apeloa.agent.chat.SessionBusyException;
import com.apeloa.agent.chat.SessionNotFoundException;
import com.apeloa.agent.chat.UnknownConfirmRequestException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 聊天/HITL 路由异常 → HTTP 映射，响应体与文件路由同形 {@code {"error":"…"}}。
 *
 * <p>401（未登录）与请求体不可读（400）已由全局 {@code FilesApiExceptionHandler} 覆盖，
 * 这里只补会话与模型相关的状态码，避免两个 advice 抢同一个异常类型。
 */
@RestControllerAdvice
public class ChatApiExceptionHandler {

    /** 错误响应体（与 {@code FilesApiExceptionHandler.ErrorResponse} 同形，前端统一读 error）。 */
    public record ErrorResponse(String error) {
    }

    @ExceptionHandler(SessionNotFoundException.class)
    public ResponseEntity<ErrorResponse> sessionNotFound(SessionNotFoundException e) {
        return body(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(UnknownConfirmRequestException.class)
    public ResponseEntity<ErrorResponse> unknownConfirm(UnknownConfirmRequestException e) {
        return body(HttpStatus.NOT_FOUND, e.getMessage());
    }

    /** 一次一条：在跑或等确认时再来 prompt → 409（前端据此提示而非重试）。 */
    @ExceptionHandler(SessionBusyException.class)
    public ResponseEntity<ErrorResponse> busy(SessionBusyException e) {
        return body(HttpStatus.CONFLICT, e.getMessage());
    }

    /** 无 Model bean：服务已就绪但对话能力不可用 → 503。 */
    @ExceptionHandler(ModelNotConfiguredException.class)
    public ResponseEntity<ErrorResponse> modelMissing(ModelNotConfiguredException e) {
        return body(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
    }

    @ExceptionHandler(BadChatRequestException.class)
    public ResponseEntity<ErrorResponse> badRequest(BadChatRequestException e) {
        return body(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    private static ResponseEntity<ErrorResponse> body(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(new ErrorResponse(message));
    }
}
