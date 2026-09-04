// 单个工具卡行：运行中脉冲点、点击展开参数/结果。名称→中文摘要由 ToolActivityGroup 给。

import { useState } from "react";
import { ChevronRight, CircleCheck, CircleAlert, Loader2 } from "lucide-react";
import { cn } from "@/lib/utils";
import type { ToolCall } from "@/lib/messages";

/** args/result → 可读 JSON 文本（截断保 UI 不爆）。 */
function pretty(value: unknown): string {
  if (value == null) return "";
  let text: string;
  try {
    text =
      typeof value === "string"
        ? value
        : JSON.stringify(value, null, 2);
  } catch {
    text = String(value);
  }
  return text.length > 2000 ? `${text.slice(0, 2000)}\n…（截断）` : text;
}

export function ToolCallRow({ call }: { call: ToolCall }) {
  const [open, setOpen] = useState(false);
  const running = call.status === "running";
  const argsText = pretty(call.args);
  const resultText = pretty(call.result);

  return (
    <div className="rounded-md border border-border/60 bg-muted/30 text-xs">
      <button
        className="flex w-full items-center gap-1.5 px-2.5 py-1.5 text-left transition-colors hover:bg-accent/40"
        onClick={() => setOpen((v) => !v)}
      >
        <ChevronRight
          className={cn("size-3.5 shrink-0 text-muted-foreground transition-transform", open && "rotate-90")}
        />
        {running ? (
          <Loader2 className="tool-live-dot size-3.5 shrink-0 animate-spin text-primary" />
        ) : call.status === "error" ? (
          <CircleAlert className="size-3.5 shrink-0 text-destructive" />
        ) : (
          <CircleCheck className="size-3.5 shrink-0 text-[#7d8f69]" />
        )}
        <span className="truncate font-mono text-[11px] text-foreground/80">{call.name}</span>
        {!running && call.status === "error" && (
          <span className="ml-auto shrink-0 text-[10px] text-destructive">失败</span>
        )}
      </button>
      {open && (
        <div className="space-y-1.5 border-t border-border/60 px-2.5 py-2">
          {argsText && (
            <div>
              <p className="mb-0.5 text-[10px] font-medium text-muted-foreground">参数</p>
              <pre className="scroll-subtle max-h-48 overflow-auto rounded bg-background/60 p-2 font-mono text-[11px] leading-relaxed whitespace-pre-wrap text-foreground/70">
                {argsText}
              </pre>
            </div>
          )}
          {resultText && (
            <div>
              <p className="mb-0.5 text-[10px] font-medium text-muted-foreground">
                {call.status === "error" ? "错误" : "结果"}
              </p>
              <pre className="scroll-subtle max-h-48 overflow-auto rounded bg-background/60 p-2 font-mono text-[11px] leading-relaxed whitespace-pre-wrap text-foreground/70">
                {resultText}
              </pre>
            </div>
          )}
          {!argsText && !resultText && (
            <p className="text-muted-foreground">无参数/结果</p>
          )}
        </div>
      )}
    </div>
  );
}
