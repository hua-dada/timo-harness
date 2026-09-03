---
name: stack-versions
description: AgentScope 2.0.2 + Spring Boot 4.0.4 + JDK 21 — the locked stack for apeloa-agent-java
metadata:
  type: project
---

复刻项目 `apeloa-agent-java`（位于 E:\people\agent-timo）锁定的技术栈版本（2026-09-02 经 Maven Central 实证确认）：

- **AgentScope Java 2.0.2**（GA，2026-08-10 发布；最新 GA 即此，2.0.3-SNAPSHOT 在 GitHub HEAD）。坐标：`io.agentscope:agentscope-harness`、`agentscope-core`、`agentscope-extensions-model-openai`、`agentscope-extensions-scheduler`、`agentscope-spring-boot-starter`、`agentscope-openai-spring-boot-starter`、BOM `agentscope-dependencies-bom`。全部 2.0.2 已在 Maven Central（repo.maven.apache.org 实测 HTTP 200）。
- **Spring Boot 4.0.4 / Java 17 基线** —— 这是 2.0.2 BOM 实际锁定的版本。本地实测解析结果（2026-09-02，`dependency:tree -Dverbose`）：Spring Framework 统一 **7.0.6**（Boot parent 4.0.4 的 dependencyManagement 优先于 import 的 agentscope BOM；spring/agentscope 相关 **零冲突**，唯一 omitted-for-conflict 是 test scope 的 opentelemetry-semconv 1.41→1.40，无害）。

**对 spec 的偏差（重要）**：docs/spec/02-tech-design.md 假设 "Spring Boot 3.3+"、PRD 写 "JDK 17+"。实际 2.0.2 BOM 强制 Spring Boot 4.0.4。因为导入 agentscope BOM 会传递拉入 Spring 7 / Boot 4，若应用用 Boot 3.3 会与 AgentScope 的 Spring 7 类冲突。故**应用层必须对齐 Spring Boot 4.0.4**（用 `spring-boot-starter-parent:4.0.4` 作 parent + import `agentscope-dependencies-bom:2.0.2`）。JDK 21（spec 目标，虚拟线程）是 17 的超集，OK。

GitHub 仓库：agentscope-ai/agentscope-java。本地工具链：JDK 21 (Temurin 21.0.12.1) + Maven 3.9.16，可本地构建验证。

相关：[[reference-project-map]] 描述被复刻的源 Node/TS 项目 E:\apeloa\apeloa-agent。
