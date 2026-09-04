// 思考块：流式中 shimmer 动画、可展开/收起；完成后折叠成「思考了 X 秒」摘要行。
// 设计借鉴源 ThinkingChainBlock：默认折叠、流式默认展开。

import { useState } from "react";
import { ChevronRight, Brain } from "lucide-react";
import { cn } from "@/lib/utils";
import { formatDuration } from "@/lib/utils";
import type { ThinkingBlock } from "@/lib/messages";

export function ThinkingChainBlock({ block }: { block: ThinkingBlock }) {
  const live = block.endedAt === undefined;
  const [open, setOpen] = useState(live);
  const seconds =
    block.startedAt != null
      ? formatDuration((block.endedAt ?? Date.now()) - block.startedAt)
      : null;

  return (
    <div className="my-1">
      <button
        className="flex w-full items-center gap-1.5 rounded-md px-1.5 py-1 text-xs text-muted-foreground transition-colors hover:bg-accent/40"
        onClick={() => setOpen((v) => !v)}
      >
        <ChevronRight
          className={cn("size-3.5 shrink-0 transition-transform", open && "rotate-90")}
        />
        <Brain className={cn("size-3.5 shrink-0", live && "animate-pulse text-primary")} />
        {live ? (
          <span className="thinking-shimmer-ltr bg-clip-text text-transparent">
            思考中…
          </span>
        ) : (
          <span>
            已思考{seconds ? `（${seconds}）` : ""}
          </span>
        )}
      </button>
      {open && (
        <pre className="scroll-subtle mt-1 mb-2 ml-6 max-h-72 overflow-y-auto rounded-md border border-border/60 bg-muted/40 px-3 py-2 font-mono text-xs leading-relaxed whitespace-pre-wrap text-muted-foreground">
          {block.text || "…"}
        </pre>
      )}
    </div>
  );
}
