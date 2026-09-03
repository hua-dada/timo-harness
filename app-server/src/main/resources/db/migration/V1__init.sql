-- M1-2: 初始 schema（PG 方言）
-- 对齐 docs/spec/03-data-api-design.md §3.1
-- 自研会话树 *_j 新增项：session_entries（树形，Jooq 裸写高频 append-only）

-- 账户（沿用源项目 org_accounts；org_sync 同步来源，骨架阶段无 org_companies/departments）
CREATE TABLE org_accounts (
    username       VARCHAR(64)  PRIMARY KEY,
    real_name      VARCHAR(128),
    mobile         VARCHAR(32),
    password_hash  VARCHAR(255),
    platform_admin BOOLEAN       NOT NULL DEFAULT FALSE,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- 会话（复用源项目 sessions；线性持久化，会话树 fork 落 session_entries）
CREATE TABLE sessions (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     VARCHAR(64)  NOT NULL REFERENCES org_accounts(username) ON DELETE CASCADE,
    title       TEXT,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_sessions_user ON sessions(user_id, updated_at DESC);

-- 会话树（自研 *_j）：parent_id 形成树形，fork=从某 entry 复制新链
CREATE TABLE session_entries (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id  UUID         NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
    parent_id   UUID         REFERENCES session_entries(id) ON DELETE CASCADE,
    kind        VARCHAR(32)  NOT NULL,                -- user / assistant / tool / summary ...
    payload     JSONB        NOT NULL DEFAULT '{}'::jsonb,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_entries_session ON session_entries(session_id, parent_id);

-- 沙箱（uid 序列 100000+，沿用源项目 pi_linux_uid_seq 思路）
CREATE TABLE sandboxes (
    user_id     VARCHAR(64)  PRIMARY KEY REFERENCES org_accounts(username) ON DELETE CASCADE,
    linux_uid   BIGINT       UNIQUE NOT NULL
);

-- uid 序列从 100000 起，避免与系统/常规用户 uid 冲突
CREATE SEQUENCE pi_linux_uid_seq START WITH 100000 INCREMENT BY 1 NO CYCLE;
