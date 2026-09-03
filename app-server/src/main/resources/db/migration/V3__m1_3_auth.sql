-- M1-3：密码登录初始账号。对齐源项目 INITIAL_PASSWORD 模式——预生成 bcrypt($2a$, cost 10)
-- 哈希写死在迁移里，明文只进文档：admin/admin123456、dev/dev123456。
-- 首次登录后应经 POST /api/auth/me/password 改密；无注册路由，后续账号走 SQL/同步（M3 org-sync）。
UPDATE org_accounts
SET password_hash = '$2a$10$9knXq2X33esc.ztsdXZ3QeBV1lIL5.O3mZl9lGto6IsrsSL4/Y2hq'
WHERE username = 'dev' AND password_hash IS NULL;

INSERT INTO org_accounts (username, real_name, password_hash, platform_admin)
VALUES ('admin', '平台管理员', '$2a$10$BLIr3yeL1SVpgUVLSbxQLuH8L9b5/Ji5PwPaJsCvUJQ.JoJGFY/Tq', TRUE)
ON CONFLICT (username) DO NOTHING;
