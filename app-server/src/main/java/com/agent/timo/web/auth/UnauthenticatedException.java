package com.agent.timo.web.auth;

/**
 * 未登录：{@link CurrentUserProvider#currentUserId()} 返回 null 时抛出，
 * web 层统一映射 401 {@code {"error":"未登录"}}（对齐源项目各 /files 路由）。
 */
public class UnauthenticatedException extends RuntimeException {

    public UnauthenticatedException() {
        super("未登录");
    }
}
