---
name: reference-project-map
description: E:\apeloa\apeloa-agent (Node/TS pi-agent) — the project being replicated to Java; key facts from 3-agent exploration
metadata:
  type: reference
---

被复刻的源项目：`E:\apeloa\apeloa-agent`（pnpm monorepo `pi-agent` v0.1.0）。Node/TS 栈：Fastify v5 + WebSocket + Drizzle(postgres-js) + pino。pi 编码 agent 作为子进程 RPC（JSONL over stdio）。**Java 复刻的进程内模型消除了 RPC/孤儿进程/JSONL 切分三层顽疾。**

三大已完成的探索结论（2026-09-02，3 个 explorer agent，完整报告在历史 transcript）：

1. **后端**（apps/server）：auth(JWT HS256 7d cookie + OIDC server-side 302/frontend-relay 双模式 + PAT HMAC)、chat(WS `/ws` 一连接一 pi 子进程)、sessions(JSONL 文件存储非 DB，fork=新建带 parentSession 头的分支文件再 detach 提升)、sandbox(共享容器 `pi-shared` + per-uid + 0700 + tini PID1 + spawn-token + pids-monitor 60s + reclaim 7d destroy-only)、plugins(6 类 SKILL/MCP/EXTENSION/TOOL/WORKFLOW/KNOWLEDGE，access 仅管 MCP/SKILL，`plugin_user_configs.values` **明文 jsonb 技术债**，secret-cipher 用硬编码 passphrase 仅传输层混淆)、tasks(node-cron + IMAP 邮件触发 + uidvalidity/lastUid 游标表)、model-metrics(vLLM Prometheus 采样存窗口增量绝对值)、audit(MDC ALS + `audit_logs`，traceId=sessionId 前 8 位)。

2. **前端/协议**（apps/web + packages/shared-types）：**复刻的承载性交付物 = 下行 SSE 事件协议**。每个 WS 帧变成一行 SSE `data:`。顶层 type：`ready|response(command∈prompt/set_model/set_thinking_level/compact/switch_session +success +error?)|error|runtime_invalidated|session_info|session_title|messages_loaded|thinking_level|fork_messages|event(包裹 PiEvent)`。PiEvent.type：`message_update(assistantMessageEvent: thinking_start|thinking_delta|text_delta|toolcall_start|toolcall_end)|tool_execution_start|tool_execution_end|agent_end|message_end(usage.totalTokens+stopReason)|extension_ui_request(唯一带 id 的 request-response)|thinking_level_changed|compaction_start|compaction_end|extension_error`。上行命令→POST：prompt/abort/compact/fork/switch_session/new_session/set_model/set_thinking_level/extension_ui_response。前端 `reduceMessage`+`reduceChatDelta`(apps/web/src/store/chat-delta.ts) 传输无关，换 ws.ts→sse.ts 不变。前端无 plan mode、无会话树 UI（仅线性+edit-resend regenerate）。

3. **包/构建**（packages/* + CI）：`shared-types` 是 DTO/枚举单一源；`packages/docker`(Dockerfile.pi = pi-sandbox 镜像，tini ENTRYPOINT + sleep infinity) 但运行时 SDK 逻辑在 apps/server/src/sandbox/；`pi-extensions/custom-provider`(new-api OpenAI 兼容 + thinkingLevelMap，off 故意不在 map) + `mcp-bridge`；`patches/@earendil-works__pi-ai@0.80.6.patch`(thinking_budget 注入，读 `NEWAPI_THINKING_BUDGETS` 发 `chat_template_kwargs.thinking_budget`，twin 在 packages/docker/patch-pi-ai-qwen-effort.mjs)；CI=.gitlab-ci.yml(DooD, build 4 image, clear-stale-sandboxes 先于 deploy)。危险命令正则 9 条在 `apps/server/src/dangerous-commands.ts`（只检测不拦截）。

相关：[[stack-versions]] 是复刻目标的 Java 侧技术栈。
