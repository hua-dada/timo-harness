package com.apeloa.agent.core.bash;

/**
 * 单条危险命中。label 供审计日志人类可读，matched 保留命中片段便于回溯。
 *
 * <p>移植自源项目 apps/server/src/dangerous-commands.ts 的 DangerousHit。
 */
public record DangerousHit(String rule, String label, String matched) {
}
