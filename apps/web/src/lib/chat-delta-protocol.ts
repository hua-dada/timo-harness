// 后端 ChatDelta 协议类型（对齐 app-server ChatDelta.java 的 14 种事件）+ SSE 载荷 →
// delta 的宽松解析。后端 data JSON 自带 type 字段（判别联合），此处只声明前端消费的形状。

export type ChatDelta =
  | { type: "session_info"; sessionId?: string; sessionName?: string }
  | { type: "messages_loaded"; messages: unknown[] }
  | { type: "thinking_level"; level: string }
  | { type: "prompt_accepted" }
  | { type: "thinking_start" }
  | { type: "thinking_delta"; text: string }
  | { type: "text_delta"; text: string }
  | { type: "tool_start"; id: string; name: string; args?: unknown }
  | { type: "tool_args"; id: string; args?: unknown }
  | { type: "tool_end"; id: string; name: string; isError: boolean; result?: unknown }
  | { type: "message_usage"; total?: number; stopReason?: string }
  | {
      type: "ui_request";
      request: { id: string; method: string; title?: string };
    }
  | { type: "agent_end" }
  | { type: "error"; message: string };

/** HITL 确认请求载荷（后端 UiRequestPayload 的消费子集；M1 method 恒 confirm）。 */
export interface ConfirmRequest {
  readonly id: string;
  readonly method: string;
  readonly title?: string;
}

/** SSE data JSON → ChatDelta；无 type 或形状不识别返回 null（未知事件静默忽略）。 */
export function parseDelta(data: unknown): ChatDelta | null {
  if (data == null || typeof data !== "object") return null;
  const d = data as { type?: unknown };
  if (typeof d.type !== "string") return null;
  return d as ChatDelta;
}
