// SSE 客户端封装（替代源 ws.ts 的角色）：EventSource 下行 + 断线自动重连。
// 与 WS 的最大差异：浏览器 EventSource 断线自动带 Last-Event-ID 重连，服务端按 seq
// 补发或全量重建（messages_loaded）——不需要源的前端静默重连状态机。
// 上行（send/abort/HITL 应答）不走这里：全部由 store 经 POST /api/… 发出。

export type SseState = "connecting" | "open" | "closed";

export interface ChatSseHandlers {
  /** 收到一条 SSE data JSON（已 parse，按 type 分发）。 */
  readonly onDelta: (delta: unknown) => void;
  /** 连接状态变化。 */
  readonly onState: (state: SseState) => void;
}

export interface ChatSse {
  /** 主动断开（不再重连）：登出 / 切换会话时调用。 */
  readonly close: () => void;
}

export function sseUrl(sessionId: string): string {
  return `/api/sessions/${encodeURIComponent(sessionId)}/events`;
}

/**
 * 后端每帧带 event: 名（= ChatDelta.type()，见 ChatController send() 的
 * frame.name(delta.type())）。SSE 规范下具名事件不触发 onmessage——必须逐名
 * addEventListener，否则一帧都收不到（联调实测踩坑）。集合对齐 ChatDelta.java
 * 的 14 种 type；未知类型 parseDelta 会静默忽略，新增类型后此处补登记即可。
 */
const EVENT_NAMES: readonly string[] = [
  "session_info",
  "messages_loaded",
  "thinking_level",
  "prompt_accepted",
  "thinking_start",
  "thinking_delta",
  "text_delta",
  "tool_start",
  "tool_args",
  "tool_end",
  "message_usage",
  "ui_request",
  "agent_end",
  "error",
];

/**
 * 建立会话 SSE 连接。返回 close()。
 * 注意：EventSource 的自动重连不可取消（除非 close()），重连后浏览器自动带
 * Last-Event-ID；服务端重建时 messages_loaded 会整体替换消息（reducer 语义）。
 */
export function createChatSse(sessionId: string, handlers: ChatSseHandlers): ChatSse {
  const source = new EventSource(sseUrl(sessionId));

  // readyState CONNECTING(0) → connecting；OPEN(1) → open；onerror 时 CLOSED(2)
  // 或自动重连（回 CONNECTING）——统一报 closed，让 UI 显示重连中。
  source.onopen = () => handlers.onState("open");

  const onDelta = (e: MessageEvent) => {
    try {
      handlers.onDelta(JSON.parse(e.data));
    } catch {
      // 非 JSON data（不会发生：后端全发 JSON）——静默忽略
    }
  };

  // 具名事件逐名注册（后端每帧带 event: 名），onmessage 只作无名帧兜底。
  for (const name of EVENT_NAMES) {
    source.addEventListener(name, onDelta as EventListener);
  }
  source.onmessage = onDelta;

  source.onerror = () => {
    if (source.readyState === EventSource.CLOSED) {
      handlers.onState("closed");
    }
    // CONNECTING = 自动重连中：报 connecting（不闪 closed）
    else handlers.onState("connecting");
  };

  return {
    close: () => source.close(),
  };
}
