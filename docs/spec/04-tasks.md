# 任务拆解（Backlog）

**文档版本**：v1.0 ｜ **上游**：[PRD](01-prd.md)。单位：人日。

## M1（MVP，~78 人日）

| ID | Task | 人日 | 依赖 |
|---|---|---|---|
| M1-1 | Maven 六模块骨架 + Boot 启动 + CI（GitHub Actions：build+test） | 3 | — |
| M1-2 | PG schema 迁移（Flyway，org_accounts/sessions/entries） | 3 | M1-1 |
| M1-3 | 密码登录 + JWT cookie + 认证上下文（Sa-Token 1.46，用户决策替代 Spring Security；已交付，契约见 03 §3.2.1） | 5 | M1-2 |
| M1-4 | AgentScope 集成 PoC：HarnessAgent + OpenAI 兼容模型跑通 new-api | 3 | M1-1 |
| M1-5 | **PoC-A**：会话消息重放一致性验证（fork 前置）（已交付 2026-09-03，结论见 03 §3.2） | 3 | M1-4 |
| M1-6 | SSE 网关桥：事件系统 → ChatDelta 协议 + 断线续传 | 8 | M1-4 |
| M1-7 | coding-tools：read/write/edit + safe-path 单测移植 | 8 | M1-4 |
| M1-8 | coding-tools：bash + 危险命令识别 + Permission/HITL 拦截 | 7 | M1-7 |
| M1-9 | Docker 沙箱：共享容器管理 + per-uid + Workspace backend 验证 | 8 | M1-7 |
| M1-10 | pids 巡检 + 容器自愈（移植） | 3 | M1-9 |
| M1-11 | 会话持久化/恢复 + entries 落盘 | 4 | M1-6 |
| M1-12 | 文件路由 + 前端复用接入（sse.ts） | 6 | M1-6 |
| M1-13 | OIDC 登录 | 4 | M1-3 |
| M1-14 | M1 联调 + E2E 冒烟 | 4 | all |

计 69 人日 + buffer ≈ 78。

## M2（核心完整，~85 人日）

| ID | Task | 人日 |
|---|---|---|
| M2-1 | 插件上传/manifest 校验/状态机（admin CRUD） | 8 |
| M2-2 | access 分配 + LoadPlan + 白名单挂载 | 7 |
| M2-3 | MCP 插件运行时（凭据插值 + per-user 参数解密注入） | 6 |
| M2-4 | **PoC-B**：Skill/MCP 热载入时机验证 | 2 |
| M2-5 | 子代理 + 计划模式启用与前端对接 | 4 |
| M2-6 | HITL 浮层前端（ui_request 桥） | 4 |
| M2-7 | 会话树 v1（fork 重生成 + tree API）（实现定案见 03 §3.2 PoC-A 结论） | 8 |
| M2-8 | Thinking Formatter（effort + budget，对比验证） | 8 |
| M2-9 | 定时任务（scheduler 集成 + cron/邮件触发 + IMAP 游标表） | 10 |
| M2-10 | 审计 + MDC traceId + 日志规范 | 6 |

## M3（企业化，~32 人日）

| ID | Task | 人日 |
|---|---|---|
| M3-1 | RBAC 完整角色模型 + SCIM 预留 | 8 |
| M3-2 | Redis 配额限流（token/会话数） | 7 |
| M3-3 | 模型指标采样 + admin 页面 | 6 |
| M3-4 | 会话树完整 UI + todo 卡片 | 6 |
| M3-5 | Playwright E2E + 50 并发压测 | 5 |
