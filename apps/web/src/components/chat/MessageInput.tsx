// 输入区：textarea 自增高、流式时切中止；HITL 确认浮层（非模态，
// absolute bottom-full 悬浮于输入框上方，借鉴源 ConfirmRequest 位置语义）。
// M1 无 modify 编辑器——只给 确认/拒绝（modify 后端支持但 UI 出界，浮层内不给入口）。

import { useRef, useState } from "react";
import { ArrowUp, Square, ShieldQuestion, CircleCheck, CircleX, Loader2 } from "lucide-react";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";
import { useChat } from "@/store/chat";
import { useUiRequests } from "@/store/ui-requests";

export function MessageInput() {
  const streaming = useChat((s) => s.delta.streaming);
  const error = useChat((s) => s.error);
  const send = useChat((s) => s.send);
  const abort = useChat((s) => s.abort);

  const [text, setText] = useState("");
  const ref = useRef<HTMLTextAreaElement>(null);

  function autoGrow() {
    const el = ref.current;
    if (!el) return;
    el.style.height = "auto";
    el.style.height = `${Math.min(el.scrollHeight, 200)}px`;
  }

  function onChange(e: React.ChangeEvent<HTMLTextAreaElement>) {
    setText(e.target.value);
    autoGrow();
  }

  function onKeyDown(e: React.KeyboardEvent<HTMLTextAreaElement>) {
    // Enter 发送、Shift+Enter 换行（CJK 输入法组词中不触发）
    if (e.key === "Enter" && !e.shiftKey && !e.nativeEvent.isComposing) {
      e.preventDefault();
      doSend();
    }
  }

  async function doSend() {
    const value = text.trim();
    if (!value || streaming) return;
    setText("");
    requestAnimationFrame(autoGrow);
    await send(value);
  }

  return (
    <div className="relative px-4 pb-4">
      <ConfirmOverlay />

      <div className="mx-auto max-w-3xl">
        {error && (
          <div className="mb-2 rounded-md border border-destructive/30 bg-destructive/10 px-3 py-2 text-xs text-destructive">
            {error}
          </div>
        )}
        <div
          className={cn(
            "flex items-end gap-2 rounded-2xl rounded-bl-md border border-border bg-card px-3.5 py-2 shadow-warm transition-colors focus-within:border-ring",
          )}
        >
          <textarea
            ref={ref}
            rows={1}
            value={text}
            onChange={onChange}
            onKeyDown={onKeyDown}
            placeholder={streaming ? "生成中…" : "给智能体发消息（Enter 发送，Shift+Enter 换行）"}
            disabled={streaming}
            className="scroll-subtle max-h-[200px] flex-1 resize-none bg-transparent py-1.5 text-sm outline-none placeholder:text-muted-foreground disabled:opacity-60"
          />
          {streaming ? (
            <Button
              size="icon"
              variant="destructive"
              className="size-8 shrink-0 rounded-lg"
              onClick={() => void abort()}
              title="中止"
            >
              <Square className="size-3.5" />
            </Button>
          ) : (
            <Button
              size="icon"
              className="size-8 shrink-0 rounded-lg"
              disabled={!text.trim()}
              onClick={() => void doSend()}
              title="发送"
            >
              <ArrowUp className="size-4" />
            </Button>
          )}
        </div>
      </div>
    </div>
  );
}

/** HITL 确认浮层：悬于输入框上方的非模态卡片。 */
function ConfirmOverlay() {
  const pending = useUiRequests((s) => s.pending);
  const responding = useUiRequests((s) => s.responding);
  const error = useUiRequests((s) => s.error);
  const respond = useUiRequests((s) => s.respond);

  if (!pending) return null;
  return (
    <div className="pointer-events-none absolute inset-x-0 bottom-full z-20 px-4 pb-3">
      <div className="pointer-events-auto mx-auto max-w-3xl rounded-2xl rounded-tl-md border border-primary/35 bg-card p-4 shadow-warm-lg">
        <div className="flex items-start gap-2.5">
          <ShieldQuestion className="mt-0.5 size-5 shrink-0 text-primary" />
          <div className="min-w-0 flex-1">
            <p className="font-brand text-sm font-medium text-foreground">
              {pending.title ?? "工具执行确认"}
            </p>
            <p className="mt-0.5 text-xs text-muted-foreground">
              智能体请求执行一个需要授权的操作，请确认是否放行。
            </p>
            {error && <p className="mt-1.5 text-xs text-destructive">{error}</p>}
            <div className="mt-3 flex gap-2">
              <Button
                size="sm"
                className="rounded-[10px]"
                disabled={responding}
                onClick={() => void respond("approve")}
              >
                {responding ? (
                  <Loader2 className="size-3.5 animate-spin" />
                ) : (
                  <CircleCheck className="size-3.5" />
                )}
                确认执行
              </Button>
              <Button
                size="sm"
                variant="destructive"
                className="rounded-[10px]"
                disabled={responding}
                onClick={() => void respond("reject")}
              >
                <CircleX className="size-3.5" />
                拒绝
              </Button>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
