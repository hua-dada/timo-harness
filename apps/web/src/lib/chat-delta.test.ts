// chat-delta reducer 单测：全 14 种 type 覆盖 + 顺序/断线容错语义。

import { describe, expect, it } from "vitest";
import {
  initialChatDeltaState,
  reduceChatDelta,
  type ChatDeltaState,
  type DeltaSideEffects,
} from "./chat-delta";
import type { ChatDelta } from "./chat-delta-protocol";

/** 依次应用 deltas，聚合 state 与副作用（uiRequests 累计、errorMessage 取最新）。 */
function apply(
  deltas: readonly ChatDelta[],
  start: ChatDeltaState = initialChatDeltaState,
): { state: ChatDeltaState; effects: DeltaSideEffects } {
  return deltas.reduce(
    (acc, d) => {
      const r = reduceChatDelta(acc.state, d);
      return {
        state: r.state,
        effects: {
          uiRequests: [...acc.effects.uiRequests, ...r.effects.uiRequests],
          errorMessage: r.effects.errorMessage ?? acc.effects.errorMessage,
        },
      };
    },
    { state: start, effects: { uiRequests: [], errorMessage: null } },
  );
}

describe("reduceChatDelta", () => {
  it("session_info 记录会话名", () => {
    const { state } = reduceChatDelta(initialChatDeltaState, {
      type: "session_info",
      sessionName: "测试会话",
    });
    expect(state.sessionName).toBe("测试会话");
  });

  it("thinking_level 更新档位", () => {
    const { state } = reduceChatDelta(initialChatDeltaState, {
      type: "thinking_level",
      level: "high",
    });
    expect(state.thinkingLevel).toBe("high");
  });

  it("messages_loaded 整体替换并退出流式", () => {
    const streaming = { ...initialChatDeltaState, streaming: true };
    const { state } = reduceChatDelta(streaming, {
      type: "messages_loaded",
      messages: [{ role: "user", content: "你好" }],
    });
    expect(state.messages).toHaveLength(1);
    expect(state.messages[0]?.role).toBe("user");
    expect(state.streaming).toBe(false);
  });

  it("完整回合：thinking → text → agent_end", () => {
    const { state } = apply([
      { type: "thinking_start" },
      { type: "thinking_delta", text: "想" },
      { type: "thinking_delta", text: "一想" },
      { type: "text_delta", text: "你好" },
      { type: "text_delta", text: "，世界" },
      { type: "agent_end" },
    ]);
    expect(state.streaming).toBe(false);
    expect(state.messages).toHaveLength(1);
    const msg = state.messages[0];
    expect(msg?.role).toBe("assistant");
    expect(msg?.done).toBe(true);
    // 块顺序：thinking（关闭）→ text；text 合并为一块
    expect(msg?.blocks).toHaveLength(2);
    expect(msg?.blocks[0]?.type).toBe("thinking");
    expect(msg?.blocks[1]?.type).toBe("text");
    if (msg?.blocks[1]?.type === "text") expect(msg.blocks[1].text).toBe("你好，世界");
    // thinking 块已关闭（text_delta 到来时）
    if (msg?.blocks[0]?.type === "thinking") expect(msg.blocks[0].endedAt).toBeDefined();
  });

  it("thinking_delta 无 start 时补块收容（断线容错）", () => {
    const { state } = apply([{ type: "thinking_delta", text: "直接来" }]);
    const msg = state.messages[0];
    expect(msg?.blocks[0]?.type).toBe("thinking");
    if (msg?.blocks[0]?.type === "thinking") expect(msg.blocks[0].text).toBe("直接来");
  });

  it("工具卡：start → args → end 全链路", () => {
    const { state } = apply([
      { type: "tool_start", id: "t1", name: "bash", args: { cmd: "ls" } },
      { type: "tool_args", id: "t1", args: { cmd: "ls -la" } },
      { type: "tool_end", id: "t1", name: "bash", isError: false, result: "ok" },
      { type: "agent_end" },
    ]);
    const msg = state.messages[0];
    expect(msg?.blocks).toHaveLength(1);
    const b = msg?.blocks[0];
    expect(b?.type).toBe("tool");
    if (b?.type === "tool") {
      expect(b.toolCall.status).toBe("done");
      expect(b.toolCall.args).toEqual({ cmd: "ls -la" });
      expect(b.toolCall.result).toBe("ok");
    }
  });

  it("tool_end isError → error 状态", () => {
    const { state } = apply([
      { type: "tool_start", id: "t1", name: "bash" },
      { type: "tool_end", id: "t1", name: "bash", isError: true, result: "boom" },
    ]);
    const b = state.messages[0]?.blocks[0];
    if (b?.type === "tool") expect(b.toolCall.status).toBe("error");
  });

  it("tool_start 同 id 幂等（重连补发）", () => {
    const { state } = apply([
      { type: "tool_start", id: "t1", name: "bash" },
      { type: "tool_start", id: "t1", name: "bash" },
    ]);
    expect(state.messages[0]?.blocks).toHaveLength(1);
  });

  it("tool_args/tool_end 未知 id 不炸", () => {
    const { state } = apply([
      { type: "tool_args", id: "nope", args: {} },
      { type: "tool_end", id: "nope", name: "bash", isError: false },
    ]);
    expect(state.messages).toHaveLength(0);
  });

  it("text → tool → text 穿插（不合并跨工具文本）", () => {
    const { state } = apply([
      { type: "text_delta", text: "先说" },
      { type: "tool_start", id: "t1", name: "bash" },
      { type: "tool_end", id: "t1", name: "bash", isError: false },
      { type: "text_delta", text: "后说" },
    ]);
    const blocks = state.messages[0]?.blocks ?? [];
    expect(blocks.map((b) => b.type)).toEqual(["text", "tool", "text"]);
  });

  it("message_usage 记录 usage 与 stopReason", () => {
    const { state } = apply([{ type: "message_usage", total: 123, stopReason: "stop" }]);
    expect(state.usageTotal).toBe(123);
    expect(state.stopReason).toBe("stop");
  });

  it("ui_request 不进消息流，副作用单发", () => {
    const r = apply([
      { type: "ui_request", request: { id: "u1", method: "confirm", title: "确认" } },
    ]);
    expect(r.state.messages).toHaveLength(0);
    expect(r.effects.uiRequests).toHaveLength(1);
    expect(r.effects.uiRequests[0]?.id).toBe("u1");
  });

  it("error 置副作用并退出流式", () => {
    const r = apply([
      { type: "text_delta", text: "x" },
      { type: "error", message: "模型超时" },
    ]);
    expect(r.state.streaming).toBe(false);
    expect(r.effects.errorMessage).toBe("模型超时");
  });

  it("prompt_accepted 幂等进入流式", () => {
    const { state } = reduceChatDelta(initialChatDeltaState, { type: "prompt_accepted" });
    expect(state.streaming).toBe(true);
  });

  it("多回合：user 边界由 messages_loaded 给，流式回合接在已有消息后", () => {
    const loaded = apply([{ type: "messages_loaded", messages: [{ role: "user", content: "第一问" }] }]);
    const next = apply([{ type: "text_delta", text: "答" }], loaded.state);
    expect(next.messages).toHaveLength(2);
    expect(next.messages[1]?.role).toBe("assistant");
  });
});
