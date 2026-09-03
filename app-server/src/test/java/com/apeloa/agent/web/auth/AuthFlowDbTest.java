package com.apeloa.agent.web.auth;

import static org.assertj.core.api.Assertions.assertThat;

import cn.hutool.crypto.digest.BCrypt;
import com.apeloa.agent.chat.persist.OrgAccountEntity;
import com.apeloa.agent.chat.persist.OrgAccountMapper;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

/**
 * M1-3 端到端：真 HTTP（RANDOM_PORT + 真 PG 测试库，Flyway 已种 admin/dev）+ 真鉴权通道——
 * {@code app.security.dev-user.enabled=false} 覆盖后 {@link SaTokenCurrentUserProvider} 生效、
 * {@link DevCurrentUserProvider} 缺席，X-User-Id 头从此无效。
 *
 * <p>账号策略：只读 seed 账号 admin（登录/守卫/错误码断言不改其行）；改密往返用专用账号
 * {@code authflow_t}（本测试自种自清，不留脏数据）。
 *
 * <p>已知对齐缺口（源项目同样如此，见 03-data-api 偏差注记）：logout 仅过期 cookie，旧 JWT
 * 签名仍有效——断言的是 cookie 过期（Max-Age=0）而非旧 token 失效；改密后旧 token 亦不吊销。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.security.dev-user.enabled=false")
@ActiveProfiles("test")
@AutoConfigureTestRestTemplate // Boot 4 拆模块后不再随 RANDOM_PORT 隐式注册
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuthFlowDbTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON =
            new ParameterizedTypeReference<>() {};

    private static final String AUTHFLOW_USER = "authflow_t";
    private static final String OLD_PW = "old-pass-123";
    private static final String NEW_PW = "new-pass-456";

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private OrgAccountMapper accounts;

    @BeforeAll
    void seedAuthflowAccount() {
        accounts.deleteById(AUTHFLOW_USER);
        OrgAccountEntity row = new OrgAccountEntity();
        row.setUsername(AUTHFLOW_USER);
        row.setPasswordHash(BCrypt.hashpw(OLD_PW, BCrypt.gensalt()));
        row.setPlatformAdmin(false);
        row.setCreatedAt(OffsetDateTime.now());
        row.setUpdatedAt(OffsetDateTime.now());
        accounts.insert(row);
    }

    @AfterAll
    void cleanupAuthflowAccount() {
        accounts.deleteById(AUTHFLOW_USER);
    }

    private ResponseEntity<Map<String, Object>> post(String path, Map<String, Object> body) {
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body), JSON);
    }

    private ResponseEntity<Map<String, Object>> get(String path, String cookie) {
        HttpHeaders headers = new HttpHeaders();
        if (cookie != null) {
            headers.add(HttpHeaders.COOKIE, cookie);
        }
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), JSON);
    }

    @Test
    void loginValidationAndErrorCodes() {
        // 400：空用户名 / 空密码
        assertThat(post("/api/auth/login", Map.of("username", "", "password", "x")).getStatusCode().value())
                .isEqualTo(400);
        assertThat(post("/api/auth/login", Map.of("username", "admin", "password", "")).getStatusCode().value())
                .isEqualTo(400);
        // 404：账号不存在
        assertThat(post("/api/auth/login", Map.of("username", "no_such_user", "password", "x"))
                        .getStatusCode().value())
                .isEqualTo(404);
        // 401：密码错误
        ResponseEntity<Map<String, Object>> wrong =
                post("/api/auth/login", Map.of("username", "admin", "password", "admin123456-x"));
        assertThat(wrong.getStatusCode().value()).isEqualTo(401);
        assertThat(wrong.getBody()).containsEntry("error", "密码错误");
    }

    @Test
    void loginSuccessIssuesJwtCookieAndBody() {
        ResponseEntity<Map<String, Object>> resp =
                post("/api/auth/login", Map.of("username", "admin", "password", "admin123456"));
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody())
                .containsEntry("userId", "admin")
                .containsEntry("username", "admin")
                .containsEntry("realName", "平台管理员")
                .containsEntry("role", "admin");

        String setCookie = resp.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        assertThat(setCookie).isNotNull();
        String c = setCookie.toLowerCase();
        assertThat(c).contains("pi_session=");
        assertThat(c).contains("httponly");
        assertThat(c).contains("samesite=lax");
        assertThat(c).contains("path=/");
        assertThat(c).contains("max-age=604800"); // 7d，非滑动
    }

    @Test
    void meAndGuardedRouteAcceptJwtCookieOnly() {
        String cookie = loginCookie("admin", "admin123456");

        ResponseEntity<Map<String, Object>> me = get("/api/auth/me", cookie);
        assertThat(me.getStatusCode().value()).isEqualTo(200);
        assertThat(me.getBody()).containsEntry("userId", "admin").containsEntry("role", "admin");

        // 真守卫贯通：无 X-User-Id 头，仅 cookie，经 SaTokenCurrentUserProvider 命中会话列表路由
        ResponseEntity<Map<String, Object>> sessions = get("/api/sessions", cookie);
        assertThat(sessions.getStatusCode().value()).isEqualTo(200);

        // 无 cookie：401，错误体与全局守卫同口径
        ResponseEntity<Map<String, Object>> anon = get("/api/auth/me", null);
        assertThat(anon.getStatusCode().value()).isEqualTo(401);
        assertThat(anon.getBody()).containsEntry("error", "未登录");

        // X-User-Id 头在此模式下不是身份来源
        HttpHeaders spoof = new HttpHeaders();
        spoof.add(HttpHeaders.COOKIE, cookie);
        spoof.add("X-User-Id", "someone-else");
        ResponseEntity<Map<String, Object>> spoofed =
                rest.exchange("/api/auth/me", HttpMethod.GET, new HttpEntity<>(spoof), JSON);
        assertThat(spoofed.getStatusCode().value()).isEqualTo(200);
        assertThat(spoofed.getBody()).containsEntry("userId", "admin");
    }

    @Test
    void forgedJwtIsRejected() {
        // 结构合法但签名密钥错误的 JWT（hutool-jwt 同款算法族）
        String forged =
                cn.hutool.jwt.JWTUtil.createToken(Map.of("sub", "admin"), "wrong-secret".getBytes(StandardCharsets.UTF_8));
        ResponseEntity<Map<String, Object>> resp = get("/api/auth/me", "pi_session=" + forged);
        assertThat(resp.getStatusCode().value()).isEqualTo(401);
        assertThat(resp.getBody()).containsEntry("error", "未登录");
    }

    @Test
    void logoutExpiresCookie() {
        String cookie = loginCookie("admin", "admin123456");
        ResponseEntity<Map<String, Object>> resp = post("/api/auth/logout", Map.of());
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody()).containsEntry("ok", true);

        String setCookie = resp.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        assertThat(setCookie).isNotNull();
        assertThat(setCookie.toLowerCase()).contains("max-age=0");

        // 客户端尊重过期 cookie（不再携带）后即未登录
        assertThat(get("/api/auth/me", null).getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void passwordChangeRoundTrip() {
        // 未登录改密 → 401
        ResponseEntity<Map<String, Object>> anon =
                post("/api/auth/me/password", Map.of("oldPassword", OLD_PW, "newPassword", NEW_PW));
        assertThat(anon.getStatusCode().value()).isEqualTo(401);

        String cookie = loginCookie(AUTHFLOW_USER, OLD_PW);
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, cookie);

        // 401：旧密码错
        ResponseEntity<Map<String, Object>> wrongOld = rest.exchange(
                "/api/auth/me/password",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("oldPassword", "not-the-old", "newPassword", NEW_PW), headers),
                JSON);
        assertThat(wrongOld.getStatusCode().value()).isEqualTo(401);

        // 400：新旧相同 / 新密码过短
        assertThat(rest.exchange(
                        "/api/auth/me/password",
                        HttpMethod.POST,
                        new HttpEntity<>(Map.of("oldPassword", OLD_PW, "newPassword", OLD_PW), headers),
                        JSON)
                        .getStatusCode()
                        .value())
                .isEqualTo(400);
        assertThat(rest.exchange(
                        "/api/auth/me/password",
                        HttpMethod.POST,
                        new HttpEntity<>(Map.of("oldPassword", OLD_PW, "newPassword", "short"), headers),
                        JSON)
                        .getStatusCode()
                        .value())
                .isEqualTo(400);

        // 200：改密成功
        ResponseEntity<Map<String, Object>> ok = rest.exchange(
                "/api/auth/me/password",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("oldPassword", OLD_PW, "newPassword", NEW_PW), headers),
                JSON);
        assertThat(ok.getStatusCode().value()).isEqualTo(200);
        assertThat(ok.getBody()).containsEntry("ok", true);

        // 旧密码登录失效，新密码登录成功
        assertThat(post("/api/auth/login", Map.of("username", AUTHFLOW_USER, "password", OLD_PW))
                        .getStatusCode()
                        .value())
                .isEqualTo(401);
        assertThat(loginCookie(AUTHFLOW_USER, NEW_PW)).isNotNull();
    }

    /** 登录成功返回 {@code pi_session=<jwt>} cookie 值；失败断言失败。 */
    private String loginCookie(String username, String password) {
        ResponseEntity<Map<String, Object>> resp =
                post("/api/auth/login", Map.of("username", username, "password", password));
        assertThat(resp.getStatusCode().value()).as("登录 %s 应成功", username).isEqualTo(200);
        String setCookie = resp.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        assertThat(setCookie).isNotNull();
        int eq = setCookie.indexOf('=');
        int semi = setCookie.indexOf(';');
        String value = setCookie.substring(eq + 1, semi < 0 ? setCookie.length() : semi);
        assertThat(value).as("cookie 应为 pi_session=<jwt>").isNotBlank();
        return "pi_session=" + value;
    }
}
