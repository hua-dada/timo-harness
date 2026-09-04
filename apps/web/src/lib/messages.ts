// 聊天消息域模型：内容块类型（ChatMessage/MessageBlock）+ 渲染分组（groupBlocks）。
// 借鉴源 apps/web src/lib/messages.ts 的语义（blocks 为内容的有序权威表示，保留模型
// 「文本→工具→文本」穿插时序），代码全新自研。
// 历史回放（messages_loaded 载荷 → ChatMessage[]）在 messages-history.ts。

/** 工具调用展示模型（实时流式与历史加载共用形状）。 */
export interface ToolCall {
  /** toolCallId，贯穿 tool_start/tool_args/tool_end 事件去重/匹配。 */
  readonly id: string;
  readonly name: string;
  readonly status: "running" | "done" | "error";
  readonly args?: unknown;
  readonly result?: unknown;
  /** 开始时间戳（tool_start 记 Date.now()）；历史回放无，省略耗时。 */
  readonly startedAt?: number;
  readonly endedAt?: number;
}

/** 文本块：一段连续正文输出。id 创建时一次性赋值，append 只换 text → React key 稳定。 */
export interface TextBlock {
  readonly type: "text";
  readonly id: string;
  readonly text: string;
}

/** 工具块：一次工具调用。id 复用 ToolCall.id。 */
export interface ToolBlock {
  readonly type: "tool";
  readonly id: string;
  readonly toolCall: ToolCall;
}

/** 思考块：一段连续思考过程。正文/工具 delta 到来时关闭记 endedAt。 */
export interface ThinkingBlock {
  readonly type: "thinking";
  readonly id: string;
  readonly text: string;
  readonly startedAt?: number;
  readonly endedAt?: number;
}

export type MessageBlock = TextBlock | ToolBlock | ThinkingBlock;

/** 单条聊天消息（user / assistant）。内容统一用 blocks 表示。 */
export interface ChatMessage {
  readonly id: string;
  readonly role: "user" | "assistant";
  readonly blocks: readonly MessageBlock[];
  readonly done: boolean;
  /** 该 assistant 消息的上下文总 token（message_usage.total；回合内取最后一条）。 */
  readonly usageTotal?: number;
  /** 回合终态原因（"length" = 截断，显式提示）。 */
  readonly stopReason?: string;
}

/** 全部文本拼接（user 消息正文 / assistant 纯文本选择器）。 */
export function textContentOf(msg: ChatMessage): string {
  return msg.blocks
    .filter((b): b is TextBlock => b.type === "text")
    .map((b) => b.text)
    .join("");
}

// ── 渲染分组 ────────────────────────────────────────────────────────────────

/** 渲染单元：一段文本 / 一段思考 / 一组连续工具调用。id 取自首个源块（React key 稳定）。 */
export type BlockGroup =
  | { readonly type: "text"; readonly id: string; readonly text: string }
  | { readonly type: "thinking"; readonly id: string; readonly block: ThinkingBlock }
  | { readonly type: "tools"; readonly id: string; readonly calls: readonly ToolCall[] };

/** 纯省略号/句点噪声（模型在工具调用前的过渡 "..."）：不独立成段，也不拆开工具组。 */
export const ELLIPSIS_NOISE = /^[\s.…。]*$/;

/**
 * blocks → 渲染单元序列：text/thinking 块独立成段，连续 tool 块合并为一组
 * （噪声 text 穿透，被 "..." 分隔的连续工具调用仍合成一卡）。
 */
export function groupBlocks(blocks: readonly MessageBlock[]): readonly BlockGroup[] {
  const groups: BlockGroup[] = [];
  let i = 0;
  while (i < blocks.length) {
    const b = blocks[i];
    if (!b) break;
    if (b.type === "text") {
      if (!ELLIPSIS_NOISE.test(b.text)) {
        groups.push({ type: "text", id: b.id, text: b.text });
      }
      i++;
      continue;
    }
    if (b.type === "thinking") {
      groups.push({ type: "thinking", id: b.id, block: b });
      i++;
      continue;
    }
    // tool 块：收集连续工具（噪声 text 穿透；thinking / 实质 text 断开）。
    const id = b.id;
    const calls: ToolCall[] = [];
    while (i < blocks.length) {
      const cur = blocks[i];
      if (!cur) break;
      if (cur.type === "text") {
        if (ELLIPSIS_NOISE.test(cur.text)) {
          i++;
          continue;
        }
        break;
      }
      if (cur.type === "thinking") break;
      calls.push(cur.toolCall);
      i++;
    }
    groups.push({ type: "tools", id, calls });
  }
  return groups;
}

/** 单调递增 id 生成（msg-1 / tb-2 / tk-3 各自独立计数）。 */
const counters = { msg: 0, tb: 0, tk: 0 };
export function newMsgId(): string {
  return `msg-${++counters.msg}`;
}
export function newTextBlockId(): string {
  return `tb-${++counters.tb}`;
}
export function newThinkingBlockId(): string {
  return `tk-${++counters.tk}`;
}
