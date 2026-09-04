// 工具活动组：连续工具块合并渲染。活跃组（含 running）直铺卡片；
// 完成组折叠成一行自然语言摘要（可点开）。

import { useState } from "react";
import { ChevronRight, Wrench } from "lucide-react";
import { cn } from "@/lib/utils";
import type { ToolCall } from "@/lib/messages";
import { ToolCallRow } from "./ToolCallRow";

/** 已知工具 → 中文动词摘要（未知工具回退 name 计数）。 */
const TOOL_LABEL: Record<string, string> = {
  bash: "执行命令",
  write_file: "写入文件",
  read_file: "读取文件",
  edit_file: "编辑文件",
  list_files: "查看目录",
  list_dir: "查看目录",
};

function summarize(calls: readonly ToolCall[]): string {
  const groups = new Map<string, number>();
  for (const c of calls) {
    groups.set(TOOL_LABEL[c.name] ?? c.name, (groups.get(c.name) ?? 0) + 1);
  }
  return [...groups.entries()]
    .map(([label, n]) => (n > 1 ? `${label} ×${n}` : label))
    .join("、");
}

export function ToolActivityGroup({ calls }: { calls: readonly ToolCall[] }) {
  const active = calls.some((c) => c.status === "running");
  const [open, setOpen] = useState(false);

  if (active) {
    return (
      <div className="my-1 space-y-1.5">
        {calls.map((c) => (
          <ToolCallRow key={c.id} call={c} />
        ))}
      </div>
    );
  }

  const failed = calls.some((c) => c.status === "error");
  return (
    <div className="my-1">
      <button
        className="flex w-full items-center gap-1.5 rounded-md px-1.5 py-1 text-xs text-muted-foreground transition-colors hover:bg-accent/40"
        onClick={() => setOpen((v) => !v)}
      >
        <ChevronRight
          className={cn("size-3.5 shrink-0 transition-transform", open && "rotate-90")}
        />
        <Wrench className={cn("size-3.5 shrink-0", failed && "text-destructive")} />
        <span className="truncate">
          {summarize(calls)}
          {failed && <span className="text-destructive">（有失败）</span>}
        </span>
      </button>
      {open && (
        <div className="mt-1 mb-2 ml-6 space-y-1.5">
          {calls.map((c) => (
            <ToolCallRow key={c.id} call={c} />
          ))}
        </div>
      )}
    </div>
  );
}
