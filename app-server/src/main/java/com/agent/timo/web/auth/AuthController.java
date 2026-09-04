package com.agent.timo.web.auth;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.crypto.digest.BCrypt;
import com.agent.timo.chat.persist.OrgAccountEntity;
import com.agent.timo.chat.persist.OrgAccountMapper;
import java.time.OffsetDateTime;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证路由（M1-3，契约对齐源 pi-agent /api/auth，偏差见 03-data-api）：
 * <ul>
 *   <li>{@code POST /api/auth/login}：JSON 200 + Set-Cookie（源是 303 跳转，记偏差）；
 *       错误码 400 空 / 404 账号不存在 / 401 密码错误（源另有 403 停用，本表无 disabled 列，不做）。</li>
 *   <li>{@code GET /api/auth/me}：401 统一 {@code {"error":"未登录"}}，与既有守卫 advice 同口径。</li>
 *   <li>{@code POST /api/auth/logout}：Simple 模式无服务端态，仅过期 cookie；旧 JWT 到期前
 *       签名仍有效（源同样只清 cookie，对齐）。</li>
 *   <li>{@code POST /api/auth/me/password}：验旧改新（bcrypt cost 10）；改密不吊销旧 token
 *       （源已知缺口，对齐）。新密码规则：非空、≥8 位、不得与旧密码相同（源精确正则未提取，
 *       按常见基线实现，记偏差）。</li>
 * </ul>
 * 无注册路由：账号由迁移 seed / 后续 org-sync（M3）创建。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final OrgAccountMapper accounts;
    private final String tokenName;

    public AuthController(
            OrgAccountMapper accounts, @Value("${sa-token.token-name:pi_session}") String tokenName) {
        this.accounts = accounts;
        this.tokenName = tokenName;
    }

    public record LoginRequest(String username, String password) {}

    public record PasswordRequest(String oldPassword, String newPassword) {}

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody LoginRequest req) {
        String username = req.username() == null ? "" : req.username().trim();
        String password = req.password() == null ? "" : req.password();
        if (username.isEmpty()) {
            return error(HttpStatus.BAD_REQUEST, "用户名不能为空");
        }
        if (password.isEmpty()) {
            return error(HttpStatus.BAD_REQUEST, "密码不能为空");
        }
        OrgAccountEntity row = accounts.selectById(username);
        if (row == null) {
            return error(HttpStatus.NOT_FOUND, "账号不存在");
        }
        // 无密码行（迁移 seed 之外的历史行）按密码错误处理，不区分提示以防账号探测
        if (row.getPasswordHash() == null || !BCrypt.checkpw(password, row.getPasswordHash())) {
            return error(HttpStatus.UNAUTHORIZED, "密码错误");
        }
        StpUtil.login(username); // 写 pi_session cookie（HttpOnly/Lax/7d 由 sa-token 配置决定）
        log.info("登录成功：username={}", username);
        return ResponseEntity.ok(view(row));
    }

    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> me() {
        OrgAccountEntity row = requireAccount();
        if (row == null) {
            return error(HttpStatus.UNAUTHORIZED, "未登录");
        }
        return ResponseEntity.ok(view(row));
    }

    @PostMapping("/logout")
    public ResponseEntity<Map<String, Object>> logout() {
        // Simple 模式下 logout 无服务端态可清；真正生效的是过期 cookie。
        try {
            StpUtil.logout();
        } catch (Exception e) {
            log.debug("StpUtil.logout 在 jwt Simple 模式下无可清会话：{}", e.getMessage());
        }
        ResponseCookie expired = ResponseCookie.from(tokenName, "")
                .httpOnly(true)
                .sameSite("Lax")
                .path("/")
                .maxAge(0)
                .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, expired.toString())
                .body(Map.of("ok", true));
    }

    @PostMapping("/me/password")
    public ResponseEntity<Map<String, Object>> changePassword(@RequestBody PasswordRequest req) {
        OrgAccountEntity row = requireAccount();
        if (row == null) {
            return error(HttpStatus.UNAUTHORIZED, "未登录");
        }
        String oldPassword = req.oldPassword() == null ? "" : req.oldPassword();
        String newPassword = req.newPassword() == null ? "" : req.newPassword();
        if (oldPassword.isEmpty() || newPassword.isEmpty()) {
            return error(HttpStatus.BAD_REQUEST, "密码不能为空");
        }
        if (newPassword.equals(oldPassword)) {
            return error(HttpStatus.BAD_REQUEST, "新密码不能与旧密码相同");
        }
        if (newPassword.length() < 8) {
            return error(HttpStatus.BAD_REQUEST, "新密码长度至少 8 位");
        }
        if (row.getPasswordHash() == null || !BCrypt.checkpw(oldPassword, row.getPasswordHash())) {
            return error(HttpStatus.UNAUTHORIZED, "未登录或原密码错误");
        }
        row.setPasswordHash(BCrypt.hashpw(newPassword, BCrypt.gensalt()));
        row.setUpdatedAt(OffsetDateTime.now());
        accounts.updateById(row);
        log.info("改密成功：username={}", row.getUsername());
        return ResponseEntity.ok(Map.of("ok", true));
    }

    /** 验签取 loginId 并复查账号行；未登录 / 账号已删返回 {@code null}。 */
    private OrgAccountEntity requireAccount() {
        Object loginId = StpUtil.getLoginIdDefaultNull();
        if (loginId == null) {
            return null;
        }
        return accounts.selectById(loginId.toString());
    }

    /** 登录//me 响应体：{userId, username, realName?, role}（realName 可空，non_null 语义手动保持）。 */
    private static Map<String, Object> view(OrgAccountEntity row) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("userId", row.getUsername());
        body.put("username", row.getUsername());
        if (row.getRealName() != null) {
            body.put("realName", row.getRealName());
        }
        body.put("role", roleOf(row.isPlatformAdmin()));
        return body;
    }

    private static String roleOf(boolean platformAdmin) {
        return platformAdmin ? "admin" : "member";
    }

    private static ResponseEntity<Map<String, Object>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("error", message));
    }
}
