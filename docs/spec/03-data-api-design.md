# 数据模型与接口设计

**文档版本**：v1.0 ｜ **上游**：[PRD](01-prd.md) ｜ [技术设计](02-tech-design.md)

## 3.1 数据库表（PostgreSQL）

沿用源项目 schema 思路，新增/调整以 `*_j` 标注：

```sql
org_accounts(username PK, real_name, mobile, password_hash, platform_admin, ...);
sessions(id PK, user_id, title, created_at, updated_at);                -- 复用
-- *_j M1-11：sessions 增列 persisted_msgs（增量落盘游标）/ last_entry_id（链尾）
session_entries(id PK, session_id, parent_id, kind, payload jsonb, created_at);
session_entries.position BIGINT GENERATED ALWAYS AS IDENTITY;          -- 同事务批量行 created_at 相同，回放顺序靠它
CREATE INDEX idx_entries_session ON session_entries(session_id, parent_id);
-- M1-11：模型上下文整档（AgentScope AgentStateStore SPI 的 DB 实现，key 固定 'agent_state'）
agent_states(user_id, session_id, state_key, payload jsonb, updated_at, PRIMARY KEY(user_id, session_id, state_key));
sandboxes(user_id PK, linux_uid UNIQUE);                                 -- uid 序列 100000+
plugins(id PK, kind, name, version, status, manifest jsonb, path);       -- 复用
plugin_access(plugin_id, scope_type, scope_id, PRIMARY KEY(plugin_id, scope_type, scope_id));
plugin_user_configs(plugin_id, user_id, values_enc bytea, PRIMARY KEY(plugin_id, user_id)); -- 信封加密 *_j
tasks(id PK, user_id, name, prompt, trigger_config jsonb, ...);
task_email_trigger_states(task_id PK, uidvalidity, last_uid);            -- 邮件 UID 游标表
audit_logs(id, tenant_id, user_id, action, detail jsonb, at);            -- 不加 trace_id，traceId=sessionId 前 8 位可 join
api_tokens / token_usage / model_metrics_samples;                        -- 照搬源项目
quotas(user_id PK, token_limit, session_limit);                          -- *_j 新增（M3）
roles / user_roles;                                                      -- *_j 新增（M3 RBAC）
```

## 3.2 核心 API

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/auth/login` / `/api/auth/oidc/**` | 密码 + OIDC（对齐源项目双模式） |
| GET | `/api/auth/me` / POST `/api/auth/logout` / POST `/api/auth/me/password` | M1-3 已交付（见 3.2.1 契约） |
| POST | `/api/sessions/{id}/messages` | 发消息（prompt），返回 202 + SSE 地址 |
| GET | `/api/sessions/{id}/events` | SSE 事件流（`Last-Event-Id` 续传） |
| POST | `/api/sessions/{id}/thinking` | 设思考档位 off/medium/xhigh |
| POST | `/api/sessions/{id}/fork` | body: `{entryId}` → 新会话（M2） |
| GET | `/api/files?path=` / POST / PATCH / DELETE | 文件树/读写（resolveUserPath 校验） |
| GET/POST/PATCH/DELETE | `/api/plugins/**`（admin）/ `/api/my-plugins/**` | 管理面 + 个人绑定（对齐源项目） |
| POST | `/api/hitl/{requestId}` | HITL 响应（approve/modify/reject + 参数） |
| GET/POST | `/api/tasks/**` / `/api/tasks/email-trigger/test` | 任务 CRUD + 邮件触发测试 |
| GET | `/api/admin/model-metrics/**` | 指标聚合（窗口重算逻辑照搬） |

**PoC-A 结论（M1-5 已验证，2026-09-03，`app-server/src/test/.../chat/replay/`）——M2-7 fork 的实现定案**：

- fork/clone（全量）= **复制 `agent_states.payload` 整档到新 sessionId**，不重放消息。模型每轮可见 prompt = `[system] + AgentState.context`，整档复制后逐条逐块与源会话相同（脚本模型等值断言实证）；真模型在 fork 出的会话里仅凭历史即可答对上一轮写入文件的口令（文件已删，读不到）。
- fork 删尾（重新生成）= 对整档 `context` 做**前缀截断**后重发该 user 消息，重放轮 prompt 与原会话当轮逐条相同。
- **内嵌 `session_id` 必须改写**：优雅停机的 state saver 用文档内 `getSessionId()` 而非槽位 key（ReActAgent#bindStateSaver），不改写会把 fork 状态写回源会话行。
- `session_entries` 是前端有损投影（`SessionEntryProjector`），反序列化回 `Msg` 直接失败，**只能服务树/历史展示，不能当重放源**；模型上下文唯一权威是 `agent_states`。

### 3.2.1 `/api/auth` 契约（M1-3 交付形态；对齐基准 = 源项目实现）

- **登录**：`POST /api/auth/login` `{username,password}` → **JSON 200 + Set-Cookie**（源是 303 跳转，**偏差**；
  本仓前后端分离，前端拿 JSON 自行跳转）。错误码：400 空 / 404 账号不存在 / 401 密码错误
  （源另有 403 停用；org_accounts 无 disabled 列，**不做，偏差**）。
- **cookie**：`pi_session`，`HttpOnly; SameSite=Lax; Path=/; Max-Age=604800`（7 天），无 secure、非滑动续期。
  值为 Sa-Token jwt Simple 模式签发的 HS256 JWT（密钥 env `JWT_SECRET`，**无默认值**——真认证模式下缺失即拒启）。
- **/me**：`GET /api/auth/me` → 200 `{userId, username, realName?, role}`；未登录 401 `{"error":"未登录"}`
  （与全部业务路由守卫同口径）。**role** = platform_admin ? "admin" : "member"。
- **登出**：`POST /api/auth/logout` → 过期 cookie（`Max-Age=0`）。Simple 模式无服务端态，旧 JWT 签名到期前
  仍有效（源同样仅清 cookie，**对齐的已知缺口**）。
- **改密**：`POST /api/auth/me/password` `{oldPassword,newPassword}` → 200 `{"ok":true}`；401 未登录/旧密码错；
  400 空 / 新旧相同 / 新密码 <8 位（源精确强度正则未提取，按常见基线，**近似**）。改密不吊销旧 token（源缺口，对齐）。
- **账号来源**：无注册路由。V3 迁移 seed `admin/admin123456`（platform_admin）与 `dev/dev123456`
  （明文仅存迁移头注释与 BCryptInteropTest）；后续账号走 SQL / M3 org-sync。
- **身份缝**：`CurrentUserProvider` 两个实现按 `app.security.dev-user.enabled` 切换——`false`（默认，真认证：
  Sa-Token 验签 + org_accounts 每请求复查）/ `true`（X-User-Id 头开发态，测试与本地联调用）。
- **多租户**：本仓无 tenant 列，隔离即 user_id 全表；认证上下文 = `AuthContext{userId, username, platformAdmin}`
  （`platformAdmin` 留作 M3 admin 路由守卫）。

## 3.3 SSE 事件协议（对齐源项目 WS 协议，前端改动最小化）

```
event: session_info | messages_loaded | tree | text_delta | tool_start | tool_end
      | ui_request | thinking_level_changed | agent_end | error
data: { ...同源项目 shared-types 语义... }
```

前端仅需：`lib/ws.ts` → `lib/sse.ts`（EventSource + POST 上行），`store/chat.ts` 的 reducer 语义不变。
