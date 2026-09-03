package com.apeloa.agent.core.bash;

import java.util.List;

/**
 * bash 工具的权限门禁：在命令执行前给出裁决。
 *
 * <p>M1-8 的 Permission/HITL 拦截点。{@link #DENY} → 工具返回错误结果不执行；
 * {@link #ASK} → 工具抛 {@link io.agentscope.core.tool.ToolSuspendException} 挂起，
 * 由框架发 {@code RequireUserConfirmEvent} 走 HITL 确认（M1-6 SSE 桥映射为
 * 前端 extension_ui_request 浮层），用户确认后恢复执行；{@link #ALLOW} → 直接执行。
 */
public interface BashPermissionGate {

    enum Decision {
        /** 放行执行。 */
        ALLOW,
        /** 挂起等待人工确认（HITL）。 */
        ASK,
        /** 拒绝执行。 */
        DENY
    }

    /**
     * 对即将执行的命令做裁决。
     *
     * @param command 完整命令字符串
     * @param hits    {@link DangerousCommandDetector} 的命中结果（可能为空）
     */
    Decision check(String command, List<DangerousHit> hits);

    /** 默认门禁：危险命中 → ASK（宁漏勿误原则下交人工裁决），未命中放行。 */
    static BashPermissionGate dangerousAsks() {
        return (command, hits) -> hits.isEmpty() ? Decision.ALLOW : Decision.ASK;
    }
}
