// chat-delta reducer（纯函数）：ChatDelta → state patch。
// 语义借鉴源 apps/web store/chat-delta.ts（块 id 稳定、末块 append、按 user 边界开新回合），
// 适配本仓后端的 ChatDelta 联合（无 fork/compaction/ready——SSE 断线续传由
// Last-Event-ID + messages_loaded 全量重建解决，无需静默重连状态机）。

import { mapHistory } from "./messages-history";
import {
  newMsgId,
  newTextBlockId,
  newThinkingBlockId,
  type ChatMessage,
  type MessageBlock,
  type ToolCall,
} from "./messages";
import type { ChatDelta } from "./chat-delta-protocol";

/** reducer 可下发的 UI 副作用（ui_request → 独立浮层 store，reducer 保持纯）。 */
export interface DeltaSideEffects {
  /** HITL 确认请求（每条一发，调用方交给 ui-requests store）。 */
  readonly uiRequests: readonly { id: string; method: string; title?: string }[];
  /** 连接错误文本（error 事件）。 */
  readonly errorMessage: string | null;
}

export interface ChatDeltaState {
  readonly messages: readonly ChatMessage[];
  readonly streaming: boolean;
  readonly sessionName: string | null;
  readonly thinkingLevel: string;
  readonly usageTotal?: number;
  readonly stopReason?: string;
}

export const initialChatDeltaState: ChatDeltaState = {
  messages: [],
  streaming: false,
  sessionName: null,
  thinkingLevel: "medium",
};

/** 末尾 assistant 消息（流式目标）；无则 null。 */
function tailAssistant(messages: readonly ChatMessage[]): ChatMessage | null {
  const last = messages[messages.length - 1];
  return last && last.role === "assistant" ? last : null;
}

function withTail(
  messages: readonly ChatMessage[],
  tail: ChatMessage,
): readonly ChatMessage[] {
  return [...messages.slice(0, -1), tail];
}

function ensureTail(state: ChatDeltaState): ChatDeltaState {
  if (tailAssistant(state.messages)) return state;
  return { ...state, messages: [...state.messages, newAssistant()] };
}

function newAssistant(): ChatMessage {
  return { id: newMsgId(), role: "assistant", blocks: [], done: false };
}

/** 关闭末尾思考块（正文/工具 delta 到来时）。 */
function closeThinking(blocks: readonly MessageBlock[]): readonly MessageBlock[] {
  const last = blocks[blocks.length - 1];
  if (last && last.type === "thinking" && last.endedAt === undefined) {
    return [...blocks.slice(0, -1), { ...last, endedAt: Date.now() }];
  }
  return blocks;
}

/**
 * 应用一条 delta，返回新 state 与副作用（一次 set 合并下发）。
 * ui_request / error 不进消息流：前者出副作用，后者由调用方置 error 提示。
 */
export function reduceChatDelta(
  state: ChatDeltaState,
  delta: ChatDelta,
): { state: ChatDeltaState; effects: DeltaSideEffects } {
  const effects: DeltaSideEffects = { uiRequests: [], errorMessage: null };

  const next = (() => {
    switch (delta.type) {
      case "session_info":
        return { ...state, sessionName: delta.sessionName ?? state.sessionName };

      case "thinking_level":
        return { ...state, thinkingLevel: delta.level };

      case "messages_loaded":
        // 全量重建：整体替换（来自 DB 历史投影），退出流式态。
        return {
          ...state,
          messages: mapHistory(delta.messages ?? []),
          streaming: false,
        };

      case "prompt_accepted":
        // 后端无此事件的失败形态（409 在 HTTP 层报），仅幂等确认流式态。
        return state.streaming ? state : { ...state, streaming: true };

      case "thinking_start": {
        const s = ensureTail(state);
        const tail = tailAssistant(s.messages)!;
        return {
          ...s,
          streaming: true,
          messages: withTail(s.messages, {
            ...tail,
            blocks: [
              ...tail.blocks,
              {
                type: "thinking",
                id: newThinkingBlockId(),
                text: "",
                startedAt: Date.now(),
              },
            ],
          }),
        };
      }

      case "thinking_delta": {
        const s = ensureTail(state);
        const tail = tailAssistant(s.messages)!;
        const last = tail.blocks[tail.blocks.length - 1];
        if (last && last.type === "thinking") {
          // append 只换 text 不换 id → React key 稳定
          return {
            ...s,
            streaming: true,
            messages: withTail(s.messages, {
              ...tail,
              blocks: [
                ...tail.blocks.slice(0, -1),
                { ...last, text: last.text + delta.text },
              ],
            }),
          };
        }
        // 无打开的思考块（丢 start 或断线重连续传）：补一块收容
        return {
          ...s,
          streaming: true,
          messages: withTail(s.messages, {
            ...tail,
            blocks: [
              ...tail.blocks,
              {
                type: "thinking",
                id: newThinkingBlockId(),
                text: delta.text,
                startedAt: Date.now(),
              },
            ],
          }),
        };
      }

      case "text_delta": {
        const s = ensureTail(state);
        let tail = tailAssistant(s.messages)!;
        let blocks = closeThinking(tail.blocks);
        const last = blocks[blocks.length - 1];
        if (last && last.type === "text") {
          blocks = [...blocks.slice(0, -1), { ...last, text: last.text + delta.text }];
        } else {
          blocks = [...blocks, { type: "text", id: newTextBlockId(), text: delta.text }];
        }
        tail = { ...tail, blocks };
        return { ...s, streaming: true, messages: withTail(s.messages, tail) };
      }

      case "tool_start": {
        const s = ensureTail(state);
        let tail = tailAssistant(s.messages)!;
        // 已有同 id 卡（断线重连续传重复建卡）：幂等跳过
        if (tail.blocks.some((b) => b.type === "tool" && b.id === delta.id)) {
          return { ...s, streaming: true };
        }
        const call: ToolCall = {
          id: delta.id,
          name: delta.name,
          status: "running",
          args: delta.args,
          startedAt: Date.now(),
        };
        tail = {
          ...tail,
          blocks: [...closeThinking(tail.blocks), { type: "tool", id: delta.id, toolCall: call }],
        };
        return { ...s, streaming: true, messages: withTail(s.messages, tail) };
      }

      case "tool_args": {
        const s = ensureTail(state);
        const tail = tailAssistant(s.messages)!;
        const idx = tail.blocks.findIndex((b) => b.type === "tool" && b.id === delta.id);
        if (idx < 0) return s;
        const b = tail.blocks[idx];
        if (b && b.type === "tool") {
          const blocks = [...tail.blocks];
          blocks[idx] = {
            type: "tool",
            id: delta.id,
            toolCall: { ...b.toolCall, args: delta.args },
          };
          return { ...s, messages: withTail(s.messages, { ...tail, blocks }) };
        }
        return s;
      }

      case "tool_end": {
        const s = ensureTail(state);
        const tail = tailAssistant(s.messages)!;
        const idx = tail.blocks.findIndex((b) => b.type === "tool" && b.id === delta.id);
        if (idx < 0) return s;
        const b = tail.blocks[idx];
        if (b && b.type === "tool") {
          const blocks = [...tail.blocks];
          blocks[idx] = {
            type: "tool",
            id: delta.id,
            toolCall: {
              ...b.toolCall,
              status: delta.isError ? "error" : "done",
              result: delta.result,
              name: delta.name || b.toolCall.name,
              endedAt: Date.now(),
            },
          };
          return { ...s, messages: withTail(s.messages, { ...tail, blocks }) };
        }
        return s;
      }

      case "message_usage":
        return {
          ...state,
          usageTotal: delta.total,
          stopReason: delta.stopReason,
        };

      case "agent_end": {
        // 回合收尾：关思考块、置 done、退出流式。
        const tail = tailAssistant(state.messages);
        if (!tail) return { ...state, streaming: false };
        return {
          ...state,
          streaming: false,
          messages: withTail(state.messages, {
            ...tail,
            done: true,
            blocks: closeThinking(tail.blocks),
          }),
        };
      }

      case "error":
        return { ...state, streaming: false };

      default:
        return state;
    }
  })();

  // ui_request 不改消息流，仅出副作用（上一分支返回原 state）。
  if (delta.type === "ui_request") {
    return {
      state,
      effects: { ...effects, uiRequests: [delta.request] },
    };
  }

  // error 不改消息流，仅出错误提示副作用（调用方置 error 并清 HITL 浮层）。
  if (delta.type === "error") {
    return { state: next, effects: { ...effects, errorMessage: delta.message } };
  }

  return { state: next, effects };
}
