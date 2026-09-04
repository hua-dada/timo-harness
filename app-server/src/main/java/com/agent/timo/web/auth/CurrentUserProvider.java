package com.agent.timo.web.auth;

import com.agent.timo.workspace.files.WorkspaceFileService;

/**
 * 当前请求的用户身份来源。M1-3（Spring Security + JWT cookie）落地前用
 * {@link DevCurrentUserProvider} 顶位；届时换实现即可，路由层无需改动。
 */
public interface CurrentUserProvider {

    /**
     * 当前请求的 userId（对应 {@code org_accounts.username}）；未登录返回 {@code null}。
     *
     * <p>返回值直接用作沙箱路径段，实现方必须保证其通过
     * {@link WorkspaceFileService#isValidUserId} 校验。
     */
    String currentUserId();
}
