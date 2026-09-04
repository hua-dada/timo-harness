---
name: m1-poc-runbook
description: How to run the M1-4 AgentScope live PoC against the enterprise new-api gateway (verified working 2026-09-02)
metadata:
  type: project
---

M1 骨架的 AgentScope 进程内链路已于 2026-09-02 **实测跑通**（M1-4 验收完成）：Spring 自动装配 → `OpenAIChatModel` → new-api 网关（192.168.100.96:3000）→ 模型 `Xiaopu-Ai` → `Flux<AgentEvent>` 类型化事件流（AGENT_START → 6×TEXT_BLOCK_DELTA → AGENT_RESULT/AGENT_END，replyId 一致），最终回复正常打印。

关键事实：
- **网关模型 id 就是 `Xiaopu-Ai`**（`GET /v1/models` 实测，无 `newapi/` 前缀）。application-poc.yml 默认值已修正。
- 网关令牌在参考项目 `E:\apeloa\apeloa-agent\apps\server\.env.example` 的 `NEWAPI_API_KEY`（真实 dev key，勿写入仓库/记忆）。本机 PG 未运行，跑 PoC 用 test profile 的 H2。
- **可用的本地跑法**（本地无 PG 时）：
  `NEWAPI_API_KEY=<key> mvn -ntp -pl app-server org.codehaus.mojo:exec-maven-plugin:3.5.0:java -Dexec.classpathScope=test -Dexec.mainClass=com.agent.timo.TimoAgentApplication -Dspring.profiles.active=poc,test`
- **坑 1**：`spring-boot:run -Dspring-boot.run.useTestClasspath=true` 虽然 profiles 都激活，但 `application-test.yml` 的 datasource 覆盖未生效（仍走 PG URL 被拒）。用上面的 exec:java 方案替代。先 `mvn -DskipTests install` 再 `-pl app-server` 单模块跑（兄弟 SNAPSHOT 需先入本地库，或加 `-am`）。
- **坑 2（重要框架行为）**：向 agentscope 自动装配的 `Toolkit` bean 注册工具，必须发生在 `ReActAgent` bean **构造之前**——Agent 构造后再注册，模型看不到工具（实测模型回复"没有提供工具"）。正确做法：`static` `BeanPostProcessor` 在 `postProcessAfterInitialization` 拦截 Toolkit 实例注册（见 `PocToolsConfig`）。`Toolkit.registerTool(Object)` 反射 `@Tool` 方法本身可靠（单测实证：read_file/list_dir/write_file/edit_file/bash 均注册成功）。
- **坑 3**：`exec:java` 在 Maven 同 JVM 跑应用，`TaskStop` 杀 shell 会孤儿化该 JVM（占住 8080）。重跑前 `netstat -ano | grep 8080` 找 PID，`wmic` 核对命令行后 `taskkill //F //PID`。
- **M1-7/8 集成实证（2026-09-02）**：模型经真实链路调用 `write_file`→`read_file`，`poc-workspace/hello.txt` 落盘内容正确，TOOL_CALL_START/TOOL_RESULT_START/TEXT_BLOCK_DELTA 事件流完整。
- 兄弟模块 jar 在 `E:\repository`（本机 Maven 本地仓库不在默认 `~/.m2`）。

相关：[[stack-versions]]、[[reference-project-map]]。
