// 消息气泡：user 右侧纯文本；assistant 左侧全宽，按 groupBlocks 分组渲染
// （text markdown ↔ thinking ↔ tools 穿插）。usage 角标在回合尾部。

import { memo } from "react";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import rehypeHighlight from "rehype-highlight";
import { groupBlocks, type ChatMessage } from "@/lib/messages";
import { ThinkingChainBlock } from "./ThinkingChainBlock";
import { ToolActivityGroup } from "./ToolActivityGroup";

export const MessageBubble = memo(function MessageBubble({
  message,
}: {
  message: ChatMessage;
}) {
  if (message.role === "user") {
    return (
      <div className="flex justify-end">
        {/* 印章式不对称圆角：右下角直角，其余圆润 */}
        <div className="max-w-[85%] rounded-2xl rounded-br-sm bg-primary px-4 py-2.5 text-sm whitespace-pre-wrap text-primary-foreground shadow-warm">
          {textContent(message)}
        </div>
      </div>
    );
  }

  const groups = groupBlocks(message.blocks);
  const hasContent = groups.length > 0;
  return (
    <div className="flex flex-col gap-0.5">
      {hasContent ? (
        groups.map((g) => {
          if (g.type === "text") {
            return (
              <div
                key={g.id}
                className="prose prose-sm max-w-none prose-pre:bg-muted/60 prose-pre:text-xs prose-code:text-xs"
              >
                <ReactMarkdown remarkPlugins={[remarkGfm]} rehypePlugins={[rehypeHighlight]}>
                  {g.text}
                </ReactMarkdown>
              </div>
            );
          }
          if (g.type === "thinking") {
            return <ThinkingChainBlock key={g.id} block={g.block} />;
          }
          return <ToolActivityGroup key={g.id} calls={g.calls} />;
        })
      ) : (
        // 空回合（流式刚建尾、尚无内容）：打字三点占位
        <div className="typing-dot flex gap-1 px-2 py-1.5" aria-label="等待回复">
          <span className="size-1.5 rounded-full bg-muted-foreground/60" />
          <span className="size-1.5 rounded-full bg-muted-foreground/60 [animation-delay:0.2s]" />
          <span className="size-1.5 rounded-full bg-muted-foreground/60 [animation-delay:0.4s]" />
        </div>
      )}
      {(message.usageTotal != null || message.stopReason) && (
        <div className="mt-1 flex items-center gap-2 text-[10px] text-muted-foreground/70">
          {message.usageTotal != null && <span>{message.usageTotal} tokens</span>}
          {message.stopReason && <span>{message.stopReason}</span>}
        </div>
      )}
    </div>
  );
});

function textContent(message: ChatMessage): string {
  return message.blocks
    .map((b) => (b.type === "text" ? b.text : ""))
    .join("");
}
