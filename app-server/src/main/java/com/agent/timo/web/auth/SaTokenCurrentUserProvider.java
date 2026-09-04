package com.agent.timo.web.auth;

import cn.dev33.satoken.stp.StpUtil;
import com.agent.timo.chat.persist.OrgAccountMapper;
import com.agent.timo.workspace.files.WorkspaceFileService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 真认证身份来源（M1-3，Sa-Token jwt Simple 模式）：cookie 里的 pi_session JWT
 * 经 {@link StpUtil#getLoginIdDefaultNull()} 验签后取 loginId（存 org_accounts.username）。
 *
 * <p>对齐源项目「每请求查库复查」：账号行被删则立即视为未登录，旧 JWT 仍无法访问
 * （username 为主键索引查询，每请求一次单行 select 开销可接受）。Simple 模式无服务端
 * 会话，改密后旧 token 到期前仍有效——源项目同样如此，已知缺口对齐（见 03-data-api 偏差注记）。
 *
 * <p>非法 loginId（不通过 {@link WorkspaceFileService#isValidUserId}，如伪造出 {@code ".."}
 * 的主体名）视为未登录，防止把身份当路径段用。
 */
@Component
@ConditionalOnProperty(name = "app.security.dev-user.enabled", havingValue = "false")
public class SaTokenCurrentUserProvider implements CurrentUserProvider {

    private final OrgAccountMapper accounts;

    public SaTokenCurrentUserProvider(OrgAccountMapper accounts) {
        this.accounts = accounts;
    }

    @Override
    public String currentUserId() {
        Object loginId = StpUtil.getLoginIdDefaultNull();
        if (loginId == null) {
            return null;
        }
        String userId = loginId.toString();
        if (!WorkspaceFileService.isValidUserId(userId)) {
            return null;
        }
        return accounts.selectById(userId) == null ? null : userId;
    }
}
