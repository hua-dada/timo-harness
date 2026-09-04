package com.agent.timo.web.auth;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.hutool.crypto.digest.BCrypt;
import org.junit.jupiter.api.Test;

/**
 * M1-3：bcrypt 哈希互认单测（无 Spring 上下文）。
 *
 * <p>V3 迁移预置的两条哈希明文是 admin/admin123456、dev/dev123456（明文只在迁移头注释
 * 与本测试出现）。这里用字面量复刻迁移里的哈希串：若有人改动 V3 的哈希而漏改明文约定，
 * 本测试立即红——防止「迁移 seed 与文档密码」漂移。hutool BCrypt（$2a$ cost 10）与源项目
 * bcryptjs 哈希格式互认，seed 才能双向登录。
 */
class BCryptInteropTest {

    /** 与 V3__m1_3_auth.sql 中 dev 行一致。 */
    private static final String DEV_HASH = "$2a$10$9knXq2X33esc.ztsdXZ3QeBV1lIL5.O3mZl9lGto6IsrsSL4/Y2hq";

    /** 与 V3__m1_3_auth.sql 中 admin 行一致。 */
    private static final String ADMIN_HASH = "$2a$10$BLIr3yeL1SVpgUVLSbxQLuH8L9b5/Ji5PwPaJsCvUJQ.JoJGFY/Tq";

    @Test
    void seedHashesVerifyDocumentedPlaintexts() {
        assertTrue(BCrypt.checkpw("dev123456", DEV_HASH), "dev seed 哈希应匹配 dev123456");
        assertTrue(BCrypt.checkpw("admin123456", ADMIN_HASH), "admin seed 哈希应匹配 admin123456");
    }

    @Test
    void seedHashesRejectWrongPlaintexts() {
        assertFalse(BCrypt.checkpw("admin123456", DEV_HASH));
        assertFalse(BCrypt.checkpw("wrong-password", ADMIN_HASH));
    }

    @Test
    void hashpwRoundTrip() {
        String hash = BCrypt.hashpw("ape-loa!密码2026", BCrypt.gensalt());
        assertTrue(hash.startsWith("$2a$10$"), "默认 cost 10，与源 bcryptjs 一致");
        assertTrue(BCrypt.checkpw("ape-loa!密码2026", hash));
        assertFalse(BCrypt.checkpw("别的密码", hash));
    }
}
