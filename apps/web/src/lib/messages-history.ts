// 历史回放：messages_loaded 载荷（后端 SessionEntryProjector 投影的 pi AgentMessage 形状）
// → ChatMessage[]。字段名易踩坑（后端契约）：thinking 不是 text、arguments 不是 args；
// 工具结果是独立 role:"toolResult" 行，按 toolCallId 回填 assistant 工具块状态。

import {
  newMsgId,
  newTextBlockId,
  newThinkingBlockId,
  type ChatMessage,
  type MessageBlock,
  type ToolCall,
} from "./messages";

/** pi 形状的宽松行类型（仅取关心字段；后端是 unknown[] 透传语义）。 */
interface HistoryRow {
  readonly role?: string;
  readonly content?: unknown;
  readonly toolCallId?: string;
  readonly isError?: boolean;
  readonly usage?: { totalTokens?: number };
  readonly stopReason?: string;
}

interface ContentPiece {
  readonly type?: string;
  readonly text?: string;
  readonly thinking?: string;
  readonly id?: string;
  readonly name?: string;
  readonly arguments?: unknown;
}

/** 从 content（string 或块数组）取全部文本。 */
export function textOf(content: unknown): string {
  if (typeof content === "string") return content;
  if (Array.isArray(content)) {
    return content
      .map((b) =>
        b && typeof b === "object" && (b as ContentPiece).type === "text"
          ? ((b as ContentPiece).text ?? "")
          : "",
      )
      .join("");
  }
  return "";
}

/**
 * 历史行数组 → ChatMessage[]。
 * 合并规则：user 行独立成消息；连续 assistant + toolResult 行合并为一个回合消息
 * （块按到达顺序穿插；toolResult 按 toolCallId 找到已建工具块回填 done/error/结果）。
 */
export function mapHistory(rows: readonly unknown[]): ChatMessage[] {
  const messages: ChatMessage[] = [];
  const openToolBlocks = new Map<string, ToolCall>();

  for (const row of rows) {
    const r = row as HistoryRow;
    if (!r || typeof r !== "object") continue;
    switch (r.role) {
      case "user": {
        messages.push({
          id: newMsgId(),
          role: "user",
          blocks: [{ type: "text", id: newTextBlockId(), text: textOf(r.content) }],
          done: true,
        });
        openToolBlocks.clear();
        break;
      }
      case "assistant": {
        const blocks: MessageBlock[] = [];
        for (const piece of Array.isArray(r.content) ? r.content : []) {
          const p = piece as ContentPiece;
          if (!p || typeof p !== "object") continue;
          if (p.type === "text" && typeof p.text === "string") {
            blocks.push({ type: "text", id: newTextBlockId(), text: p.text });
          } else if (p.type === "thinking" && typeof p.thinking === "string") {
            blocks.push({
              type: "thinking",
              id: newThinkingBlockId(),
              text: p.thinking,
              startedAt: undefined,
              endedAt: 0,
            });
          } else if (p.type === "toolCall" && typeof p.id === "string") {
            const call: ToolCall = {
              id: p.id,
              name: p.name ?? "?",
              status: "running",
              args: p.arguments,
            };
            blocks.push({ type: "tool", id: p.id, toolCall: call });
            openToolBlocks.set(p.id, call);
          }
        }
        messages.push({
          id: newMsgId(),
          role: "assistant",
          blocks,
          done: true,
          usageTotal:
            typeof r.usage?.totalTokens === "number" && r.usage.totalTokens > 0
              ? r.usage.totalTokens
              : undefined,
          stopReason: typeof r.stopReason === "string" ? r.stopReason : undefined,
        });
        break;
      }
      case "toolResult": {
        const target = r.toolCallId != null ? openToolBlocks.get(r.toolCallId) : undefined;
        // 正常路径：找到本回合的活跃工具块。找不到（投影缺 assistant 行等）就丢弃——
        // 孤儿结果无处渲染，硬造卡片反而凭空多出「?」工具。
        if (target) {
          const last = messages[messages.length - 1];
          if (last && last.role === "assistant") {
            const idx = last.blocks.findIndex(
              (b) => b.type === "tool" && b.id === target.id,
            );
            if (idx >= 0) {
              const b = last.blocks[idx];
              if (b && b.type === "tool") {
                const patched: ToolCall = {
                  ...b.toolCall,
                  status: r.isError ? "error" : "done",
                  result: r.content,
                  endedAt: 0,
                };
                const blocks = [...last.blocks];
                blocks[idx] = { type: "tool", id: target.id, toolCall: patched };
                messages[messages.length - 1] = { ...last, blocks };
                openToolBlocks.set(target.id, patched);
              }
            }
          }
        }
        break;
      }
      default:
        break;
    }
  }
  return messages;
}
