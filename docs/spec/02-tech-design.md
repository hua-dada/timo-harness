# 技术设计方案（Tech Design）

**文档版本**：v1.0 ｜ **上游**：[PRD](01-prd.md)

## 2.1 技术栈锁定

| 层 | 选型 | 版本锁定策略 |
|---|---|---|
| JDK | 21（LTS，虚拟线程用于 SSE 长连接） | 精确 |
| 核心框架 | `io.agentscope:agentscope-harness` + `agentscope-extensions-model-openai` + `agentscope-extensions-scheduler` | 2.0.x 精确锁定（BOM），吸取源项目 pi-* caret 漂移教训 |
| 应用框架 | Spring Boot 3.3+（WebMVC + SSE，不用 WebFlux） | 3.3.x |
| 安全 | 密码登录：Sa-Token 1.46（boot4-starter + sa-token-jwt Simple 无状态模式）；企业 SSO（OIDC）：M1-13 再选型 | 1.46.x 精确（用户决策，替代 Spring Security） |
| 数据 | PostgreSQL 16 + MyBatis-Plus（审计/插件/任务/会话） | — |
| 缓存/限流 | Redis 7（配额计数 + 令牌桶） | — |
| 沙箱 | Docker（共享容器 `pi-shared-java` + per-uid exec） | — |

## 2.2 核心链路设计

### A. 聊天链路（替代源项目 WS + RPC）

```
React(SSE EventSource)
 → POST /api/sessions/{id}/messages（建立 SSE，或复用框架 Agent 服务）
 → SessionManager（MyBatis-Plus）：校验租户/加载 HarnessAgent
 → agent.run(msg) → 事件系统 Flux<Ev>（类型化事件流）
 → SSE 桥：Ev → 前端 ChatDelta（对齐源项目 reduceMessage 语义）
   text_output → text_delta
   tool_call/tool_result → tool_start/tool_end
   hitl_request → ui_request（前端浮层，响应 POST /api/hitl/{id}）
```

- 每会话单 Agent 实例缓存在 `ConcurrentHashMap<sessionId, AgentHandle>`，空闲 30min 驱逐（落盘后可重建）。
- SSE 断线：框架「可续传流」+ `Last-Event-Id` 补发。
- **不引入子进程**：无 JSONL 切分、无孤儿进程、无 spawn-token 治理。

### B. 沙箱链路（M1）

- 沿用源项目模型：共享容器常驻 + `docker exec --user <uid>` + 目录 0700 + tini PID 1 + pids 巡检（Java 侧 `ScheduledExecutorService` 60s）。
- Bash/Edit/Write 工具执行走 AgentScope Workspace Docker backend（若其 exec 语义不足——**需验证** uid 隔离粒度——则自研 `DockerExecBackend` 实现 Workspace SPI，保留官方 Local/E2B 可切换性）。
- 文件 HTTP 路由：Java 进程以 root 或 sudo-whitelist 读写 per-uid 目录（同源项目），`resolveUserPath` 防穿越逻辑移植。

### C. 插件链路（M2）

```
上传 zip → manifest 校验（类型/权限/参数声明）→ 解包至 pluginsRoot/{id}
分配 → access 表（user/workspace scope）
WS/SSE 建连 → LoadPlanResolver：按用户解析依赖闭包
→ MCP 类：构建 McpServerConfig（${PLUGIN_DIR} 路径插值 + $VAR 凭据插值）
→ 沙箱启动时 bind mount 白名单（只挂已授权插件目录，物理隔离）
→ 装配进 Toolkit；变更走会话边界生效（保守方案）+ 前端提示重连
```

## 2.3 模块划分（Maven）与依赖方向

```
app-server → agent-plugins → agent-core → (agentscope-harness)
           → agent-tasks  ↗   agent-workspace ↗
           → agent-admin  ↗
```

## 2.4 关键技术决策记录（ADR 摘要）

| # | 决策 | 理由 |
|---|---|---|
| 1 | WebMvc+SSE 而非 WS | SSE 单向下行够用（上行走 POST），框架原生 SSE 支持，省 WS 握手/心跳复杂度 |
| 2 | 会话消息等高频 append-only 写走 Mapper 裸 insert（实体 + BaseMapper，无逻辑删除/自动填充） | M1-11 实施时数据层统一切到 MyBatis-Plus 3.5.17（原定 Jooq 裸写/JPA，用户决策变更）；append-only 语义以「只用 insert + selectList」体现，替代原「不上 ORM」表述 |
| 3 | 不 fork AgentScope，仅锁版本 | 先验证两个 PoC，必要时再 fork 单点补丁 |
| 4 | 虚拟线程承载 SSE 长连接 | 50 并发下避免平台线程占用 |
| 5 | 认证框架 Sa-Token 1.46（boot4-starter + sa-token-jwt Simple 模式）替代 Spring Security | M1-3 实施时用户决策变更；Sa-Token 1.45+ 正式支持 Boot 4，无状态 JWT cookie 一等公民，无需 Security 过滤器链；OIDC/企业 SSO 仍留待 M1-13 届时选型（可另引 oauth2-client，与密码登录并存） |
