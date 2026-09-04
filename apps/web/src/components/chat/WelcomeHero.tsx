// 欢迎页（未选中/未新建会话时的中栏）：
// 时段问候（衬线大字）+ 居中 composer + 建议卡片。发送即 newSession + send。
// 借鉴 Claude.ai 新会话页范式，视觉沿用侘寂 token（纸面/衬线/暖阴影）。

import { useRef, useState } from "react";
import {
  ArrowUp,
  Loader2,
  FileText,
  FolderOpen,
  Terminal,
  Sparkles,
  type LucideIcon,
} from "lucide-react";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";
import { useAuth } from "@/store/auth";
import { useChat } from "@/store/chat";

interface Suggestion {
  icon: LucideIcon;
  title: string;
  prompt: string;
}

const SUGGESTIONS: readonly Suggestion[] = [
  {
    icon: FileText,
    title: "写一首俳句",
    prompt: "在工作区写一首关于秋日纸窗的俳句，存成 haiku.txt",
  },
  {
    icon: FolderOpen,
    title: "盘点工作区",
    prompt: "看看工作区里都有什么文件，挑重要的读一读并总结",
  },
  {
    icon: Terminal,
    title: "跑条命令",
    prompt: "用 bash 查一下当前的日期和时间",
  },
  {
    icon: Sparkles,
    title: "自我介绍",
    prompt: "介绍一下你自己：能做什么、有哪些工具、哪些操作需要我确认",
  },
];

export function WelcomeHero() {
  const me = useAuth((s) => s.me);
  const newSession = useChat((s) => s.newSession);
  const send = useChat((s) => s.send);

  const [text, setText] = useState("");
  const [starting, setStarting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const ref = useRef<HTMLTextAreaElement>(null);

  function autoGrow() {
    const el = ref.current;
    if (!el) return;
    el.style.height = "auto";
    el.style.height = `${Math.min(el.scrollHeight, 160)}px`;
  }

  function onChange(e: React.ChangeEvent<HTMLTextAreaElement>) {
    setText(e.target.value);
    autoGrow();
  }

  function onKeyDown(e: React.KeyboardEvent<HTMLTextAreaElement>) {
    if (e.key === "Enter" && !e.shiftKey && !e.nativeEvent.isComposing) {
      e.preventDefault();
      void start(text);
    }
  }

  /** 新建会话并发送首条消息（composer 直接发 / 点建议卡填充）。 */
  async function start(prompt: string) {
    const value = prompt.trim();
    if (!value || starting) return;
    setStarting(true);
    setError(null);
    try {
      await newSession();
      await send(value);
    } catch (e) {
      setError(e instanceof Error ? e.message : "创建会话失败");
      setStarting(false);
    }
  }

  const name = me?.realName || me?.username;

  return (
    <div className="scroll-subtle flex flex-1 flex-col items-center justify-center overflow-y-auto px-4 py-10">
      <div className="flex w-full max-w-2xl flex-col items-center">
        <p className="text-sm text-muted-foreground">
          {greeting()}，{name ?? "旅人"}
        </p>
        <h1 className="font-brand mt-2 text-center text-2xl font-medium text-foreground sm:text-3xl">
          今天想做点什么？
        </h1>
        <p className="mt-2 text-center text-xs text-muted-foreground/70">
          timo-agent 已就绪 —— 直接开口，或从下面挑一件事开始。
        </p>

        <div className="mt-8 w-full">
          <div
            className={cn(
              "flex items-end gap-2 rounded-2xl rounded-bl-md border border-border bg-card px-3.5 py-2 shadow-warm transition-colors focus-within:border-ring",
            )}
          >
            <textarea
              ref={ref}
              rows={2}
              value={text}
              onChange={onChange}
              onKeyDown={onKeyDown}
              disabled={starting}
              placeholder="给智能体发消息（Enter 发送，Shift+Enter 换行）"
              className="scroll-subtle max-h-[160px] flex-1 resize-none bg-transparent py-1.5 text-sm outline-none placeholder:text-muted-foreground disabled:opacity-60"
            />
            <Button
              size="icon"
              className="size-8 shrink-0 rounded-lg"
              disabled={starting || !text.trim()}
              onClick={() => void start(text)}
              title="发送"
            >
              {starting ? (
                <Loader2 className="size-4 animate-spin" />
              ) : (
                <ArrowUp className="size-4" />
              )}
            </Button>
          </div>
          {error && (
            <p className="mt-2 px-1 text-xs text-destructive">{error}</p>
          )}
        </div>

        <div className="mt-6 grid w-full grid-cols-1 gap-2.5 sm:grid-cols-2">
          {SUGGESTIONS.map((s) => (
            <SuggestionCard
              key={s.title}
              suggestion={s}
              disabled={starting}
              onPick={() => {
                setText(s.prompt);
                requestAnimationFrame(() => {
                  autoGrow();
                  ref.current?.focus();
                });
              }}
            />
          ))}
        </div>
      </div>
    </div>
  );
}

function SuggestionCard({
  suggestion: { icon: Icon, title, prompt },
  disabled,
  onPick,
}: {
  suggestion: Suggestion;
  disabled: boolean;
  onPick: () => void;
}) {
  return (
    <button
      type="button"
      disabled={disabled}
      onClick={onPick}
      className="group rounded-xl rounded-tl-sm border border-border bg-card/70 px-3.5 py-3 text-left shadow-warm transition-colors hover:border-ring hover:bg-card disabled:pointer-events-none disabled:opacity-50"
    >
      <span className="flex items-center gap-2 text-sm font-medium text-foreground">
        <Icon className="size-4 text-primary" />
        {title}
      </span>
      <span className="mt-1 line-clamp-2 block text-xs leading-relaxed text-muted-foreground">
        {prompt}
      </span>
    </button>
  );
}

/** 按本地时间给问候语。 */
function greeting(): string {
  const h = new Date().getHours();
  if (h < 5) return "夜深了";
  if (h < 11) return "早上好";
  if (h < 13) return "中午好";
  if (h < 18) return "下午好";
  return "晚上好";
}
