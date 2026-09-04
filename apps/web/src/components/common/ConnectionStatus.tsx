// 连接状态点：侘寂色系——open=青苔绿、connecting=土陶脉冲、closed=赭红。

import { cn } from "@/lib/utils";
import type { SseState } from "@/lib/sse";

const LABEL: Record<SseState, string> = {
  open: "已连接",
  connecting: "连接中…",
  closed: "已断开",
};

export function ConnectionStatus({ state }: { state: SseState }) {
  return (
    <span className="inline-flex items-center gap-1.5 text-xs text-muted-foreground">
      <span
        className={cn(
          "size-2 rounded-full",
          state === "open" && "bg-[#7d8f69]",        // 苔绿
          state === "connecting" && "animate-pulse bg-[#a8946a]", // 土陶
          state === "closed" && "bg-[#a4463c]",       // 赭红
        )}
      />
      {LABEL[state]}
    </span>
  );
}
