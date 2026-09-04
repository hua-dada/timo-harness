// 消息流：流式平滑跟随（距底近才自动滚）、历史全量替换瞬切到底、
// 顶部加载态（SSE 未 open 且无消息时显示骨架）。

import { useEffect, useLayoutEffect, useRef, useState } from "react";
import { useChat } from "@/store/chat";
import { Skeleton } from "@/components/ui/skeleton";
import { MessageBubble } from "./MessageBubble";

/** 距底部多少像素内算「贴底」（跟随/瞬切到底的判定阈值）。 */
const STICK_THRESHOLD = 120;

export function MessageList() {
  const messages = useChat((s) => s.delta.messages);
  const streaming = useChat((s) => s.delta.streaming);
  const sseState = useChat((s) => s.sseState);
  const activeSessionId = useChat((s) => s.activeSessionId);

  const containerRef = useRef<HTMLDivElement>(null);
  const stick = useRef(true);
  // 识别全量替换（messages_loaded / 切会话）：消息 id 集合变化大 → 瞬切到底
  const prevIds = useRef<string>("");
  const [firstRender, setFirstRender] = useState(true);

  const ids = messages.map((m) => m.id).join(",");
  const replaced =
    !firstRender && !isPrefixAppend(prevIds.current, ids) && prevIds.current !== ids;
  prevIds.current = ids;

  useLayoutEffect(() => {
    const el = containerRef.current;
    if (!el) return;
    setFirstRender(false);
    if (stick.current || replaced) {
      el.scrollTop = el.scrollHeight;
    }
  }, [ids, replaced]);

  useEffect(() => {
    // 切会话：重置贴底
    stick.current = true;
  }, [activeSessionId]);

  function onScroll() {
    const el = containerRef.current;
    if (!el) return;
    stick.current = el.scrollHeight - el.scrollTop - el.clientHeight < STICK_THRESHOLD;
  }

  const waiting = sseState !== "open" && messages.length === 0;

  return (
    <div
      ref={containerRef}
      onScroll={onScroll}
      className="scroll-subtle flex-1 overflow-y-auto px-4 py-6"
    >
      <div className="mx-auto flex max-w-3xl flex-col gap-6">
        {waiting && (
          <div className="space-y-3 py-8">
            <Skeleton className="h-4 w-40" />
            <Skeleton className="h-16 w-full max-w-lg" />
            <Skeleton className="h-4 w-24" />
          </div>
        )}
        {messages.map((m) => (
          <MessageBubble key={m.id} message={m} />
        ))}
        {streaming && messages.length === 0 && (
          <div className="typing-dot flex gap-1 px-2 py-1">
            <span className="size-1.5 rounded-full bg-muted-foreground/60" />
            <span className="size-1.5 rounded-full bg-muted-foreground/60 [animation-delay:0.2s]" />
            <span className="size-1.5 rounded-full bg-muted-foreground/60 [animation-delay:0.4s]" />
          </div>
        )}
      </div>
    </div>
  );
}

/** 旧 id 串是「旧串 + 尾部追加若干新 id」时返回 true（流式追加，非全量替换）。 */
function isPrefixAppend(prev: string, next: string): boolean {
  return prev !== "" && (next === prev || next.startsWith(`${prev},`));
}
