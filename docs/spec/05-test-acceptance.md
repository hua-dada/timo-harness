# 测试与验收计划

**文档版本**：v1.0 ｜ **上游**：[PRD](01-prd.md) ｜ [任务拆解](04-tasks.md)

## 5.1 测试策略

| 层 | 内容 |
|---|---|
| 单测 | safe-path 穿越、危险命令正则、manifest 校验、凭据插值、thinking 档位映射——**全部用例移植源项目 vitest 对应集**（JUnit5） |
| 集成 | PG：会话持久化/树 fork 重放；Docker 沙箱 uid 隔离（A 读写 B 目录必败）。**偏差（M1-11）**：原定 Testcontainers 在离线构建环境不可装，经用户决策改为专用真 PG 测试库 `agent_timo_test`（111.91.21.112，test profile 连接串见 application-test.yml），DB 集成测试依赖该库可达 |
| 契约 | SSE 事件 schema 用 JSON Schema 固化，前端共用 schema 校验 |
| E2E | Playwright：登录→对话→工具卡→危险命令确认→文件浏览→断线续传→会话恢复 |
| 对比 | 双平台同题集（含源项目 2026-08 思考预算实证题）：验证 thinking budget 注入效果 |

## 5.2 里程碑验收门（可勾选）

### M1
- [ ] 50 并发 SSE 稳定 30min
- [ ] 沙箱跨 uid 访问全部 Permission denied
- [ ] 危险命令 100% 拦截
- [ ] 重启后会话/文件完整
- [ ] E2E 冒烟绿

### M2
- [ ] admin 上传 MCP 插件→用户绑定→工具可调全链路
- [ ] secret 全密文（DB 无明文）
- [ ] fork 重生成与源项目语义一致
- [ ] 邮件触发含 UID 游标断点续采
- [ ] 审计可按 traceId 串联

### M3
- [ ] 配额超限 429 + 审计
- [ ] RBAC 越权用例全拒
- [ ] 压测报告（50 并发）
- [ ] 安全审计（路径穿越/zip 炸弹/注入）通过

## 5.3 上线策略

灰度：先 5 个内部用户双平台并行（同一前端可切后端），对比两周故障率/满意度后放量。
