/**
 * agent-tasks —— scheduler 集成 + 邮件触发（IMAP 轮询 + uidvalidity/lastUid 游标表）。
 * 复用 AgentScope extensions-scheduler 调度，自研邮件轮询。
 *
 * <p>骨架阶段：仅占位包（已声明 agentscope-extensions-scheduler 依赖）。M2-9 落实现。
 */
package com.agent.timo.tasks;
