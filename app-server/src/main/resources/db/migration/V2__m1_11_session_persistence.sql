-- M1-11: 会话持久化/恢复 + entries 落盘（03-data-api-design §3.1 增量）

-- ① dev 账号占位：dev-user 身份（app.security.dev-user.id=dev）在 org_accounts 无行，
--    sessions.user_id 外键会挡住建会话。M1-3 真鉴权落地后此行可删。
INSERT INTO org_accounts (username, real_name)
VALUES ('dev', '开发态用户（M1-3 前占位）')
ON CONFLICT (username) DO NOTHING;

-- ② 模型上下文整体落盘：AgentStateStore 的 DB 实现（DbAgentStateStore），
--    payload = AgentState 经框架全局 JsonCodec 序列化的单个 JSON 文档
--    （List 形态按框架 JsonFileAgentStateStore 惯例存 JSONL）。session_id 带 FK 级联：
--    删会话即清状态，无需业务侧补删。
CREATE TABLE agent_states (
    user_id    VARCHAR(64) NOT NULL,
    session_id UUID        NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
    state_key  VARCHAR(64) NOT NULL,
    payload    JSONB       NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, session_id, state_key)
);

-- ③ entries 落盘（前端历史 + M2-7 会话树 fork 的父链）：
--    persisted_msgs = 已从 AgentState.context 投影进 session_entries 的 Msg 条数（增量游标）；
--    last_entry_id  = entries 链尾（下批首行的 parent_id）。
ALTER TABLE sessions ADD COLUMN persisted_msgs INT NOT NULL DEFAULT 0;
ALTER TABLE sessions ADD COLUMN last_entry_id UUID;

-- 同事务批量插入的行共享 created_at（now() 事务级同值），回放排序需要确定性键：
-- position 全局单调，INSERT 顺序即会话内顺序。
ALTER TABLE session_entries ADD COLUMN position BIGINT GENERATED ALWAYS AS IDENTITY;
CREATE UNIQUE INDEX idx_entries_position ON session_entries(position);
