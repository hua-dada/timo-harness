# 《apeloa-agent Java 复刻项目 PRD + 架构设计方案》

**技术基线**：AgentScope Java 2.0.x（GA 2026-07，最新 2.0.1）｜Spring Boot 3.x｜JDK 17+｜PostgreSQL
**文档版本**：v1.0
**关联文档**：[技术设计方案](02-tech-design.md) ｜ [数据模型与接口设计](03-data-api-design.md) ｜ [任务拆解](04-tasks.md) ｜ [测试与验收计划](05-test-acceptance.md)

---

## 一、产品概述

### 1.1 复刻动机

| 动机 | 说明 |
|---|---|
| 技术栈统一 | 企业核心业务团队为 Java 栈，Node/TS 的 Pi SDK 运维与二开能力储备不足 |
| 消除架构顽疾 | 源项目「网关 ↔ RPC 子进程 ↔ 容器」三层链路的 PID 治理、孤儿进程、严格 JSONL 切分等复杂度，在 AgentScope 进程内模型下大部分消失 |
| 框架红利 | AgentScope 2.0 内置源项目大量自研能力：沙箱工作区、会话持久化、子代理、计划模式、HITL、权限系统、多租户 Agent 服务、定时任务 |
| 生产化路径 | Java 生态（Sa-Token/JWT / MDC / JPA）比源项目自建 JWT/OIDC、pino+ALS 更成熟 |

### 1.2 目标用户

企业内部全员（member）+ 平台管理员（admin），与源项目一致：员工通过浏览器与 Coding Agent 对话、管理文件、使用插件与定时任务；admin 管理插件、监控模型与审计。

### 1.3 与源项目的差异边界

| 决策 | 内容 |
|---|---|
| **做** | 聊天+流式、工具调用、文件浏览、多租户+JWT/OIDC、插件/技能、任务调度+邮件触发、模型监控、审计、会话持久化 |
| **做但重构** | 沙箱隔离（改用框架 Workspace/Sandbox 抽象 + 自研 per-uid 加固）、Coding 工具集（自研于框架 Toolkit 之上） |
| **砍/降级** | 会话树 fork/clone 首期降级为「线性会话 + 分支另存」；POSIX ACL 信息隔离改为「目录不可见 + 白名单挂载」 |
| **增强** | RBAC 完整化、配额限流（源项目阶段 5 缺口，复刻时一并设计） |

---

## 二、能力映射表

工作量：人日（1 名熟练 Java 工程师）。P0=MVP 必需，P1=核心完整，P2=增强。

| 源项目模块 | AgentScope 2.0 对应能力 | 复用方式 | 工作量 | 优先级 |
|---|---|---|---|---|
| pi RPC 子进程（JSONL、事件广播） | 进程内 ReActAgent + **事件系统**（类型化流式事件，天然可接 SSE/AG-UI） | 直接复用 | 5（SSE 网关对接） | P0 |
| 流式输出/工具调用前端 | 事件流 → SSE；前端 React 保留，替换 ws.ts 协议层 | 二次开发 | 10 | P0 |
| custom-provider（new-api 网关） | `agentscope-extensions-model-openai`（OpenAI 兼容栈） | 直接复用 | 3 | P0 |
| 文件浏览/上传（HTTP files 路由） | 无对应（前端直连需求） | 完全自研 | 5 | P0 |
| 多租户三层隔离 | **智能体服务**内置多租户 + 工作区按 user/agent/session 隔离（官方 `builder` 示例即多租户平台） | 复用+扩展 | 8 | P0 |
| JWT/OIDC 登录 | Sa-Token + sa-token-jwt（用户决策变更；OIDC 仍待 M1-13 选型） | 完全自研（成熟方案） | 8 | P0 |
| 沙箱（共享容器+per-uid DAC+PID 治理） | **工作区系统**：Local/Docker/E2B 一行切换 + 工具沙箱 | 复用（Docker backend）+自研 per-uid 加固 | 12 | P0/P1 |
| Coding 工具集（read/edit/write/bash/危险命令识别） | Toolkit/@Tool 注解 + 权限系统拦截；**coding 工具本体无内置** | 完全自研 | 25 | P0 |
| 会话持久化/恢复 | **会话持久化**（自动保存、重启恢复） | 直接复用 | 2 | P0 |
| 会话树 fork/clone | 无对应抽象 | 完全自研 | 15 | P1（首期降级） |
| pi-subagents 子代理 | 内置 Subagent 系统 | 直接复用 | 2 | P1 |
| pi-plan-mode 计划模式 | 内置 Plan Mode（只读思考+HITL） | 直接复用 | 2 | P1 |
| ask-user-question UI 桥接 | 内置 HITL（审核/修改参数/精确恢复）+ SSE 事件 | 复用+前端对接 | 6 | P1 |
| rpiv-todo | 无对应（或经 Skill 实现） | 自研（Tool + 前端卡片） | 5 | P2 |
| MCP 桥接 | 内置 MCP 客户端支持 | 直接复用 | 2 | P1 |
| Plugin Registry（6 类+权限+绑定+加密） | Skill 系统 + 权限系统；市场/上传/分配无对应 | 自研管理面（复用运行时） | 25 | P1 |
| Thinking 档位（effort+budget 注入） | 模型 formatter 可扩展 | 二次开发 | 8 | P1 |
| 定时任务+邮件触发 | `extensions-scheduler` + Spring starter | 复用调度，自研邮件轮询 | 10 | P1 |
| 模型指标采样（vLLM） | 无对应 | 完全自研（照搬逻辑） | 6 | P2 |
| 审计+traceId | MDC + AOP | 自研（范式成熟） | 6 | P1 |
| RBAC + 配额限流 | 权限系统覆盖工具级；平台 RBAC/配额自研 | 自研 | 15 | P2 |

**汇总**：P0 ≈ 78 人日（MVP），P1 ≈ 85 人日，P2 ≈ 32 人日。总约 4-5 人月。

---

## 三、自研重点设计

### 3.1 Coding 工具集（P0，最大工作量）

AgentScope 是通用 Agent 框架，无 coding 工具本体。设计：

```
coding-tools 模块（@Tool 注解注册进 Toolkit）
├─ ReadFileTool     —— 路径安全校验（防 ../ 穿越，移植 safe-path 单测）+ 图片 base64（视觉模型）
├─ EditTool         —— oldText 精确匹配替换（对齐源项目语义，非行号 diff）
├─ WriteTool        —— 原子写（tmp+rename）
├─ BashTool         —— 经 Workspace 沙箱执行；危险命令识别（移植 dangerous-commands.ts 正则集）
└─ GrepTool/FindTool
```

- 权限系统挂载：`edit/write/bash` 声明为需审批工具 → 走框架 Permission/HITL，天然复刻源项目「危险命令确认」交互。
- 上下文压缩（长对话/超大工具输出）：框架内置 Compaction，无需自研。

### 3.2 会话树 fork/clone（P1，差异化功能）

AgentScope Session 是线性持久化。设计映射层：

- 存储模型沿用源项目思想：`session_entries` 表（`id`/`parent_id`/`payload jsonb`），树形。
- fork = 从某 entry 起复制新链 → **复制 `agent_states` 整档到新 sessionId 即可，无需消息重放**（M1-5 PoC-A 已验证：整档复制后模型可见上下文与源会话逐条相同，含工具调用/结果块穿 jsonb 无损）。
- clone = 复制当前链尾为新会话。
- MVP 降级：仅「从任意 user 消息重新生成」（=fork 删尾），完整树 UI 后移。

### 3.3 插件管理面（P1）

- 运行时：MCP server 类插件 → 框架 MCP 客户端按用户动态装配；SKILL/EXTENSION → 工作区 Skill 目录 + **runtime-invalidation** 重载（AgentScope 技能热载入，**需验证**运行中 session 生效时机，保守方案=下次会话生效 + 前端提示）。
- 管理面（自研，Spring CRUD）：上传 zip → manifest 校验（类型/权限声明，移植源项目校验逻辑）→ 范围分配 → per-user 参数（secret 用 Jasypt/信封加密，直接修掉源项目明文技术债）。
- 信息隔离：放弃 POSIX ACL，改为 per-user 沙箱工作区内**只挂载已授权插件**（Docker bind mount 白名单），未授权 = 物理不可见，强于源方案。

### 3.4 企业网关细粒度控制（P1）

- 自定义 Model Formatter（扩展 OpenAI 兼容栈）：注入 `reasoning_effort`（off/medium/xhigh 三档，off → `enable_thinking:false`）+ `chat_template_kwargs.thinking_budget`（Qwen3 软约束收口，移植源项目补丁语义，Java 侧实现更直接）。
- `NEWAPI_VISION_MODELS`/`NEWAPI_THINKING_MODELS` 白名单 env 同款双解析（模型能力声明 + 前端路由标志）。

---

## 四、系统架构

```
┌─────────────────────────────────────────────────┐
│ 前端 React 19（复用 apps/web，替换 ws.ts→SSE 客户端） │
└───────────────┬─────────────────────────────────┘
                │ HTTPS + SSE + cookie JWT/OIDC
┌───────────────▼─────────────────────────────────┐
│ Spring Boot 3 网关（api-server 模块）              │
│  auth(Security+OIDC) · files · sessions · plugins │
│  admin(模型监控/审计) · SSE 事件桥(事件系统→SSE)     │
├─────────────────────────────────────────────────┤
│ AgentScope HarnessAgent 层（进程内，无 RPC 子进程）  │
│  coding-tools · HITL/Permission · Plan Mode       │
│  Subagent · Skill/MCP 装配 · Thinking Formatter   │
│  Workspace(Local/Docker/E2B) + per-uid 加固       │
├─────────────────────────────────────────────────┤
│ PostgreSQL(会话/插件/任务/审计) · Redis(配额/限流)    │
│ Docker 沙箱(共享容器 + per-uid，保留源项目隔离模型)    │
└─────────────────────────────────────────────────┘
```

**Maven 多模块**（对标官方仓库结构）：

```
apeloa-agent-java/
├─ pom.xml                    (BOM：锁 agentscope 2.0.x)
├─ agent-core/                # Coding 工具集、Thinking Formatter、危险命令识别
├─ agent-workspace/           # per-uid 沙箱加固、插件白名单挂载、文件路由
├─ agent-plugins/             # Plugin Registry 管理面 + 运行时装配
├─ agent-tasks/               # scheduler 集成 + 邮件触发（IMAP 轮询）
├─ agent-admin/               # 模型指标采样、审计、RBAC/配额
└─ app-server/                # Spring Boot 主应用（聚合 + SSE 网关）
```

**关键简化收益**：源项目三大顽疾（RPC JSONL 切分、孤儿进程/PID 治理、pi 版本锁死五处同步）在进程内模型下**整体消失**；sandbox spawn-token/tini 仍保留（Docker backend 下工具执行仍需）。

---

## 五、关键风险与对策

| # | 风险 | 等级 | 对策 |
|---|---|---|---|
| 1 | AgentScope Java 生态年轻（1.0→2.0 仅 8 个月），生产 bug 需自踩 | 高 | 锁定 2.0.x 精确版本（同源项目 pnpm.overrides 教训）；深入模块源码建立内部 fork 能力；核心路径（事件流/会话）写集成测试 |
| 2 | ReActAgent ≠ 完整 coding agent，工具质量决定体验 | 高 | coding 工具集单人专攻 + 移植源项目全部单测（safe-path/危险命令）；灰度对比两平台同题输出 |
| 3 | 会话树回放后 AgentScope 内部状态（memory/compaction）一致性 | 中 | **已验证**（M1-5 PoC-A）：2.0.2 无 compaction（`summary` 恒空），fork=整档复制 `agent_states`，重放 prompt 与源逐条相同；真模型实证通过。结论详见 03 §3.2 |
| 4 | 技能/MCP 运行中热载入时机不明 | 中 | **需验证**；保守=会话边界生效 |
| 5 | Qwen thinking_budget 行为差异（Java formatter vs pi-ai 补丁） | 中 | 同题采样对比验证 budget 注入效果 |
| 6 | 双平台并存期运维成本 | 低 | 前端共用（同一 React 应用两个后端），切换成本最小化 |

---

## 六、分期交付计划

### M1：MVP（~78 人日，8-10 周）
- [ ] Spring Boot 骨架 + AgentScope 集成 + OpenAI 兼容模型接入（new-api）
- [ ] JWT/OIDC 登录 + 多租户上下文
- [ ] SSE 流式聊天 + 工具调用（前端复用，协议层替换）
- [ ] Coding 工具集 v1（read/edit/write/bash + 路径安全 + 危险命令拦截 + HITL 确认）
- [ ] 工作区 Docker 沙箱 + per-uid 隔离
- [ ] 会话持久化/恢复（线性）
- [ ] 文件浏览/上传路由

**验收**：员工登录后与 Agent 完整对话：流式输出、工具调用卡片（含危险命令确认）、文件改动落盘且可浏览、刷新后会话恢复。

### M2：核心完整（~85 人日）
- [ ] 插件管理面（上传/分配/参数加密/白名单挂载）+ MCP 插件运行时
- [ ] 子代理、计划模式、ask-user-question 前端浮层（HITL 桥接）
- [ ] 会话树 v1（fork 重生成）
- [ ] Thinking 档位三档 + budget 注入（对比验证）
- [ ] 定时任务 + 邮件触发
- [ ] 审计日志 + MDC traceId

**验收**：admin 上传 MCP 插件并分配；用户在聊天中触发插件工具；`/plan` 只读模式可用；任务按 cron/邮件触发执行。

### M3：企业化（~32 人日）
- [ ] RBAC 完整角色模型 + SCIM 预留
- [ ] Redis 配额限流（token/会话数）
- [ ] 模型指标采样页
- [ ] 会话树完整 UI、todo 卡片
- [ ] E2E（Playwright，复用源项目场景）+ 50 并发压测

**验收**：配额超限返回 429 且审计可查；压测 50 并发 SSE 稳定；E2E 全绿。

---

## 七、非功能需求

| 维度 | 要求 |
|---|---|
| 性能 | ≥50 并发 SSE 会话；首 token < 3s；工具调用事件端到端 < 200ms |
| 安全 | 租户三层隔离（目录/沙箱/路由）渗透测试通过；secret 全密文存储；插件包上传校验（zip 炸弹/路径穿越） |
| 可观测 | MDC traceId 贯穿 HTTP/SSE/工具调用/任务；SSE 断线可续传（框架内置可续传流） |
| 可用性 | 会话状态全落 PG，进程重启无感恢复；沙箱容器自愈（保留源项目 inspect 重建逻辑） |
| 兼容 | 前端与源项目共用一套，双后端可切换 |

---

**下一步建议**：先做两个「需验证」项的 PoC（① 会话消息重放一致性，② Skill/MCP 热载入时机），各约 2-3 人日，结果直接决定 M1/M2 排期细节。
