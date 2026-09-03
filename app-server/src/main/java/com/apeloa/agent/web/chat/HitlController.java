package com.apeloa.agent.web.chat;

import com.apeloa.agent.chat.AgentSession;
import com.apeloa.agent.chat.AgentSessionManager;
import com.apeloa.agent.chat.UnknownConfirmRequestException;
import com.apeloa.agent.web.auth.CurrentUserProvider;
import com.apeloa.agent.web.auth.UnauthenticatedException;
import java.util.Locale;
import java.util.Map;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * HITL 应答路由（M1-6）：前端浮层收到 ui_request 后回这里，会话据此续跑同一回合。
 *
 * <p>源协议的 ui-response 只带 requestId（WS 天然绑定会话），这里保持同样的载荷形状，
 * 由服务端在<b>当前用户</b>的会话里反查目标会话（{@link AgentSessionManager#findByConfirmRequest}）
 * ——找不到即 404，不会跨用户命中。
 *
 * <p>三种 action 对应框架的 ConfirmResult 语义：approve = 原样执行；modify = 用回传 args 原地替换
 * ToolUseBlock 后执行（框架 applyConfirmResults 支持改参）；reject = 写入拒绝结果，且由会话层自行
 * 补发 tool_end（框架拒绝路径不发事件）。
 */
@RestController
@RequestMapping("/api/hitl")
public class HitlController {

    private final AgentSessionManager sessions;
    private final CurrentUserProvider currentUser;

    public HitlController(AgentSessionManager sessions, CurrentUserProvider currentUser) {
        this.sessions = sessions;
        this.currentUser = currentUser;
    }

    /** 应答体：action = approve / modify / reject；args 仅 modify 用（改后的完整入参）。 */
    public record HitlRequest(String action, Map<String, Object> args) {
    }

    public record HitlResponse(boolean ok) {
    }

    @PostMapping("/{requestId}")
    public HitlResponse respond(@PathVariable String requestId, @RequestBody HitlRequest body) {
        String userId = requireUserId();
        AgentSession session =
                sessions.findByConfirmRequest(userId, requestId)
                        .orElseThrow(() -> new UnknownConfirmRequestException(requestId));
        switch (actionOf(body)) {
            case "approve" -> session.resolveConfirm(requestId, true, null);
            case "modify" -> {
                if (body.args() == null || body.args().isEmpty()) {
                    throw new BadChatRequestException("modify 必须带 args");
                }
                session.resolveConfirm(requestId, true, body.args());
            }
            case "reject" -> session.resolveConfirm(requestId, false, null);
            default -> throw new BadChatRequestException("action 必须是 approve、modify 或 reject");
        }
        return new HitlResponse(true);
    }

    private static String actionOf(HitlRequest body) {
        if (body == null || body.action() == null) {
            return "";
        }
        return body.action().strip().toLowerCase(Locale.ROOT);
    }

    private String requireUserId() {
        String userId = currentUser.currentUserId();
        if (userId == null) {
            throw new UnauthenticatedException();
        }
        return userId;
    }
}
