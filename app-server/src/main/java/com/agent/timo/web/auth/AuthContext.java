package com.agent.timo.web.auth;

import com.agent.timo.chat.persist.OrgAccountEntity;

/**
 * 已认证请求的上下文（M1-3 最小形态）。本仓的「多租户」就是 user 级隔离
 * （org_accounts 无 tenant 列，见 03-data-api §3.1 偏差注记），故不含 tenant 字段；
 * M3 引入 admin 路由时以 {@link #platformAdmin()} 做守卫。
 *
 * @param userId        org_accounts.username（全库 user_id 外键目标）
 * @param username      同 userId（本表主键即用户名）
 * @param platformAdmin 平台管理员标记
 */
public record AuthContext(String userId, String username, boolean platformAdmin) {

    static AuthContext of(OrgAccountEntity row) {
        return new AuthContext(row.getUsername(), row.getUsername(), row.isPlatformAdmin());
    }
}
