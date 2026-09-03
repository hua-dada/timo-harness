/**
 * agent-core —— Coding 工具集（Read/Write/Edit/Bash）、危险命令识别、沙箱路径安全。
 * 框架无关 Java 库，构建于 AgentScope Toolkit/@Tool 之上。
 *
 * <ul>
 *   <li>{@code files}：SafePath 路径防穿越（M1-7，移植自源项目 safe-path.ts）</li>
 *   <li>{@code tools}：read_file/write_file/edit_file coding 工具（M1-7）</li>
 *   <li>{@code bash}：bash 工具 + 9 条危险命令规则 + Permission/HITL 门禁 + CommandExecutor SPI（M1-8；
 *       M1-9 沙箱落地时提供 Docker 实现）</li>
 *   <li>M2-8：Thinking Formatter（effort + budget）</li>
 * </ul>
 */
package com.apeloa.agent.core;
