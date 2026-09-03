package com.apeloa.agent.web.auth;

import com.apeloa.agent.workspace.files.WorkspaceFileService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 开发态身份来源（M1-3 之前的占位）：取请求头 {@code X-User-Id}，缺失时回落
 * {@code app.security.dev-user.id}（默认 {@code dev}）。
 *
 * <p><b>没有任何鉴权</b>：任何调用方都能自称任意用户，从而读写该用户 workspace。
 * 仅供本机开发/联调；M1-3（Spring Security + JWT cookie）落地后须把
 * {@code app.security.dev-user.enabled} 置 false 并改用 JWT 实现。启动时打 WARN 提示。
 *
 * <p>非法 userId（不通过 {@link WorkspaceFileService#isValidUserId}）视为未登录，
 * 防止把 ".." 之类的身份当成路径段用。
 */
@Component
@ConditionalOnProperty(name = "app.security.dev-user.enabled", matchIfMissing = true)
public class DevCurrentUserProvider implements CurrentUserProvider {

    /** 开发态身份请求头。 */
    public static final String USER_HEADER = "X-User-Id";

    private static final Logger log = LoggerFactory.getLogger(DevCurrentUserProvider.class);

    private final HttpServletRequest request;
    private final String defaultUserId;

    public DevCurrentUserProvider(
            HttpServletRequest request,
            @Value("${app.security.dev-user.id:dev}") String defaultUserId) {
        this.request = request;
        this.defaultUserId = defaultUserId;
        log.warn("已启用开发态身份来源（{} / app.security.dev-user.id={}）：文件路由当前无真实鉴权，"
                + "M1-3 接入 JWT 后须置 app.security.dev-user.enabled=false", USER_HEADER, defaultUserId);
    }

    @Override
    public String currentUserId() {
        String header = request.getHeader(USER_HEADER);
        String candidate = header == null || header.isBlank() ? defaultUserId : header.trim();
        return WorkspaceFileService.isValidUserId(candidate) ? candidate : null;
    }
}
