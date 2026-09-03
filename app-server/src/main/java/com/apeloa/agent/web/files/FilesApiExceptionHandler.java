package com.apeloa.agent.web.files;

import com.apeloa.agent.core.files.PathEscapeException;
import com.apeloa.agent.web.auth.UnauthenticatedException;
import com.apeloa.agent.workspace.files.WorkspaceFileException;
import com.apeloa.agent.workspace.files.WorkspaceFileService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;

/**
 * 文件路由异常 → HTTP 映射，响应体统一 {@code {"error":"…"}}（前端 files-api.ts 的 jsonError 读该字段）。
 *
 * <p>用全局 {@code @RestControllerAdvice} 而非 controller 内 {@code @ExceptionHandler}：multipart
 * 超限在 DispatcherServlet 的 checkMultipart 阶段抛出，此时 handler 尚未解析，controller 局部
 * handler 不会命中。
 */
@RestControllerAdvice
public class FilesApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(FilesApiExceptionHandler.class);

    /** 错误响应体（对齐源项目 {@code {error}}）。 */
    public record ErrorResponse(String error) {
    }

    /** 上限文案由常量推导，避免配置与文案漂移。 */
    static String tooLargeMessage() {
        return "文件过大（>" + WorkspaceFileService.MAX_UPLOAD_BYTES / 1024 / 1024 + "MB）";
    }

    @ExceptionHandler(UnauthenticatedException.class)
    public ResponseEntity<ErrorResponse> unauthenticated(UnauthenticatedException e) {
        return body(HttpStatus.UNAUTHORIZED, "未登录");
    }

    /** 路径越权：不回显用户传入的路径（源亦只回固定文案）。 */
    @ExceptionHandler(PathEscapeException.class)
    public ResponseEntity<ErrorResponse> pathEscape(PathEscapeException e) {
        log.debug("路径越权被拦截：{}", e.getMessage());
        return body(HttpStatus.BAD_REQUEST, "路径越权");
    }

    @ExceptionHandler(WorkspaceFileException.class)
    public ResponseEntity<ErrorResponse> workspaceFile(WorkspaceFileException e) {
        HttpStatus status = switch (e.kind()) {
            case BAD_REQUEST, NOT_A_FILE, ROOT_PROTECTED -> HttpStatus.BAD_REQUEST;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case TOO_LARGE -> HttpStatus.CONTENT_TOO_LARGE;
            case CONFLICT -> HttpStatus.CONFLICT;
            case IO_FAILED -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
        if (status.is5xxServerError()) {
            log.warn("文件操作失败", e);
        }
        return body(status, e.getMessage());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> tooLarge(MaxUploadSizeExceededException e) {
        return body(HttpStatus.CONTENT_TOO_LARGE, tooLargeMessage());
    }

    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<ErrorResponse> multipart(MultipartException e) {
        return body(HttpStatus.BAD_REQUEST, "解析上传失败：" + e.getMessage());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> unreadableBody(HttpMessageNotReadableException e) {
        return body(HttpStatus.BAD_REQUEST, "请求体格式错误");
    }

    private static ResponseEntity<ErrorResponse> body(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(new ErrorResponse(message));
    }
}
