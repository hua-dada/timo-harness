// chat store（核心状态机）：组合 SSE 下行（sse.ts）+ ChatDelta reducer（chat-delta.ts）
// + HTTP 上行（api.ts）。语义借鉴源 store/chat.ts + chat-ws-actions.ts：
// - 乐观 user 消息：POST 前先入列（SSE 的 thinking_start 可能先于 POST 返回到达，
//   先入列才能保证 ensureTail 把 assistant 回合接在 user 之后）；失败回滚。
// - 上行全部 POST，与 SSE 连接解耦；断线续传交给 EventSource + Last-Event-ID。
// - HITL ui_request 经 reducer 副作用转 ui-requests store，此处不碰消息流。

import { create } from "zustand";
import {
  createSession,
  listSessions,
  sendMessage,
  abortSession,
  type SessionMeta,
} from "@/lib/api";
import { createChatSse, type ChatSse, type SseState } from "@/lib/sse";
import {
  initialChatDeltaState,
  reduceChatDelta,
  type ChatDeltaState,
} from "@/lib/chat-delta";
import { parseDelta } from "@/lib/chat-delta-protocol";
import { newMsgId, type ChatMessage } from "@/lib/messages";
import { useUiRequests } from "./ui-requests";

interface ChatState {
  readonly sessions: readonly SessionMeta[];
  readonly activeSessionId: string | null;
  readonly delta: ChatDeltaState;
  readonly sseState: SseState;
  /** 发送/连接错误（一次性提示，下次发送前清）。 */
  readonly error: string | null;
  readonly listLoading: boolean;

  loadSessions: () => Promise<void>;
  /** 新建并切换。返回 sessionId（失败 throw）。 */
  newSession: (name?: string) => Promise<string>;
  /** 切换会话：关旧连接、重置消息、连新 SSE（messages_loaded 全量重建）。 */
  switchSession: (sessionId: string) => void;
  /** 发送：乐观 user 消息 + POST；409 等错误回滚并置 error。 */
  send: (message: string) => Promise<void>;
  /** 中止当前回合：POST abort；agent_end/error 经 SSE 到达后自然收尾。 */
  abort: () => Promise<void>;
  /** 登出/卸载时彻底断开。 */
  disconnect: () => void;
}

/** 模块级连接句柄：一次只持一条（对齐源 createChatWs 单连接）。 */
let sse: ChatSse | null = null;

function closeSse() {
  if (sse) {
    sse.close();
    sse = null;
  }
}

export const useChat = create<ChatState>((set, get) => {
  /** SSE delta → reducer → 合并 state + 分发副作用。 */
  function applyDelta(data: unknown) {
    const delta = parseDelta(data);
    if (!delta) return;
    const { state, effects } = reduceChatDelta(get().delta, delta);

    if (effects.uiRequests.length > 0) {
      const req = effects.uiRequests[0];
      if (req) useUiRequests.getState().push(req);
    }
    if (effects.errorMessage != null) {
      // error → 后端已中止回合且 HITL 按拒绝处理，清浮层
      useUiRequests.getState().clear();
      set({ delta: state, error: effects.errorMessage });
      return;
    }
    if (delta.type === "agent_end") useUiRequests.getState().clear();
    set({ delta: state });
  }

  function connect(sessionId: string) {
    closeSse();
    set({ sseState: "connecting" });
    sse = createChatSse(sessionId, {
      onDelta: applyDelta,
      onState: (s) => set({ sseState: s }),
    });
  }

  return {
    sessions: [],
    activeSessionId: null,
    delta: initialChatDeltaState,
    sseState: "closed",
    error: null,
    listLoading: false,

    loadSessions: async () => {
      set({ listLoading: true });
      try {
        const sessions = await listSessions();
        set({ sessions });
      } finally {
        set({ listLoading: false });
      }
    },

    newSession: async (name) => {
      const meta = await createSession(name);
      set({ sessions: [meta, ...get().sessions] });
      get().switchSession(meta.sessionId);
      return meta.sessionId;
    },

    switchSession: (sessionId) => {
      if (get().activeSessionId === sessionId) return;
      useUiRequests.getState().clear();
      closeSse();
      set({
        activeSessionId: sessionId,
        delta: initialChatDeltaState,
        error: null,
        sseState: "connecting",
      });
      connect(sessionId);
    },

    send: async (message) => {
      const { activeSessionId, delta } = get();
      const text = message.trim();
      if (!activeSessionId || !text || delta.streaming) return;

      // 乐观 user 消息（POST 前入列，保证 SSE 早到的 assistant 回合顺序正确）
      const optimistic: ChatMessage = {
        id: newMsgId(),
        role: "user",
        blocks: [{ type: "text", id: `tb-${newMsgId()}`, text }],
        done: true,
      };
      set({ delta: { ...delta, messages: [...delta.messages, optimistic] }, error: null });

      try {
        await sendMessage(activeSessionId, text);
      } catch (e) {
        // 回滚乐观消息，保留错误提示
        set({
          delta: {
            ...get().delta,
            messages: get().delta.messages.filter((m) => m.id !== optimistic.id),
          },
          error: e instanceof Error ? e.message : "发送失败",
        });
      }
    },

    abort: async () => {
      const { activeSessionId } = get();
      if (!activeSessionId) return;
      try {
        await abortSession(activeSessionId);
      } catch (e) {
        set({ error: e instanceof Error ? e.message : "中止失败" });
      }
    },

    disconnect: () => {
      useUiRequests.getState().clear();
      closeSse();
      set({
        activeSessionId: null,
        delta: initialChatDeltaState,
        sseState: "closed",
        error: null,
      });
    },
  };
});
