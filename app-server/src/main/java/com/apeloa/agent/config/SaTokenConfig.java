package com.apeloa.agent.config;

import cn.dev33.satoken.jwt.StpLogicJwtForSimple;
import cn.dev33.satoken.stp.StpLogic;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Sa-Token 集成（M1-3）：jwt 插件 Simple 模式——纯无状态 JWT，token 即签名载荷，
 * 服务端不存会话（对齐源项目 pi_session 契约：logout 只清 cookie、无吊销）。
 *
 * <p>Simple 模式下 sa-token 的 {@code StpUtil.login()} 签发 HS256 JWT 并按
 * {@code sa-token.*} 配置写 cookie；{@code StpUtil.getLoginIdDefaultNull()} 验签后取
 * loginId（我们存 org_accounts.username）。会话类 API（kickout 等）在此模式不可用，
 * 与源契约一致，不使用。
 */
@Configuration
public class SaTokenConfig {

    /** 签名密钥：仅用于启动校验；实际验签由 sa-token 读 sa-token.jwt-secret-key。 */
    @Value("${sa-token.jwt-secret-key:agent-timo}")
    private String jwtSecretKey;

    /** 真认证模式下密钥缺失必须拒启（避免重蹈 timo/timo123 硬编码）。 */
    @Value("${app.security.dev-user.enabled:true}")
    private boolean devUserEnabled;

    @Bean
    public StpLogic stpLogicJwtForSimple() {
        return new StpLogicJwtForSimple();
    }

    @PostConstruct
    void requireJwtSecretWhenAuthenticating() {
        if (!devUserEnabled && jwtSecretKey.isBlank()) {
            throw new IllegalStateException(
                    "app.security.dev-user.enabled=false（真认证模式）要求 JWT 签名密钥："
                            + "请设置环境变量 JWT_SECRET（sa-token.jwt-secret-key）");
        }
    }
}
