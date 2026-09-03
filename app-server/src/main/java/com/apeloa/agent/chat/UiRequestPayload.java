package com.apeloa.agent.chat;

/**
 * HITL 请求载荷，形状对齐源项目 {@code shared-types/pi-events.ts} 的
 * {@code ExtensionUiRequestEvent}（uuid id + method + 标题等可选字段），使前端
 * {@code store/ui-requests.ts} 浮层渲染逻辑无需改动。
 *
 * <p>M1-6 来源是 AgentScope 的 {@code RequireUserConfirmEvent}（BashTool 危险命令 ASK 挂起），
 * method 固定 {@code confirm}；扩展字段（options/placeholder/timeout）保留给 M2 的
 * dialog 类子协议（select/input/editor）。
 */
public record UiRequestPayload(
        String id,
        String method,
        String title,
        java.util.List<String> options,
        String placeholder,
        Long timeout) {

    public static UiRequestPayload confirm(String id, String title) {
        return new UiRequestPayload(id, "confirm", title, null, null, null);
    }
}
