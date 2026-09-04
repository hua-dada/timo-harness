// 输入区：textarea 自增高、流式时切中止；附件（按钮/拖拽/粘贴）先传 workspace、
// 发送时在消息末尾追加「附件：」引用行（sys-prompt 约定 agent 据此 read_file）；
// HITL 确认浮层（非模态，absolute bottom-full 悬浮于输入框上方）。
// M1 无 modify 编辑器——只给 确认/拒绝（modify 后端支持但 UI 出界，浮层内不给入口）。

import { useRef, useState } from "react";
import {
  ArrowUp,
  Square,
  ShieldQuestion,
  CircleCheck,
  CircleX,
  Loader2,
  Paperclip,
  X,
  FileText,
} from "lucide-react";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";
import { uploadFile } from "@/lib/api";
import { useChat } from "@/store/chat";
import { useUiRequests } from "@/store/ui-requests";

/** 一个待随消息引用的附件：选中即上传，成功记 path，失败记 error（可重试/移除）。 */
interface Attachment {
  readonly id: string;
  readonly name: string;
  readonly path: string | null;
  readonly error: string | null;
  readonly uploading: boolean;
}

let attachSeq = 0;

export function MessageInput() {
  const streaming = useChat((s) => s.delta.streaming);
  const error = useChat((s) => s.error);
  const send = useChat((s) => s.send);
  const abort = useChat((s) => s.abort);

  const [text, setText] = useState("");
  const [attachments, setAttachments] = useState<readonly Attachment[]>([]);
  const [dragging, setDragging] = useState(false);
  const ref = useRef<HTMLTextAreaElement>(null);
  const fileRef = useRef<HTMLInputElement>(null);

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

  /** 选中文件即并行上传；逐个回填 path / error（失败不阻塞发送，仅不进引用行）。 */
  function pickFiles(files: Iterable<File>) {
    for (const file of files) {
      const id = `att-${++attachSeq}`;
      setAttachments((prev) => [
        ...prev,
        { id, name: file.name, path: null, error: null, uploading: true },
      ]);
      uploadFile(file)
        .then((path) => {
          setAttachments((prev) =>
            prev.map((a) =>
              a.id === id ? { ...a, path, uploading: false } : a,
            ),
          );
        })
        .catch((e: unknown) => {
          setAttachments((prev) =>
            prev.map((a) =>
              a.id === id
                ? {
                    ...a,
                    uploading: false,
                    error: e instanceof Error ? e.message : "上传失败",
                  }
                : a,
            ),
          );
        });
    }
  }

  function removeAttachment(id: string) {
    setAttachments((prev) => prev.filter((a) => a.id !== id));
  }

  function onKeyDown(e: React.KeyboardEvent<HTMLTextAreaElement>) {
    // Enter 发送、Shift+Enter 换行（CJK 输入法组词中不触发）
    if (e.key === "Enter" && !e.shiftKey && !e.nativeEvent.isComposing) {
      e.preventDefault();
      void doSend();
    }
  }

  function onPaste(e: React.ClipboardEvent<HTMLTextAreaElement>) {
    const files = Array.from(e.clipboardData.files);
    if (files.length > 0) {
      e.preventDefault();
      pickFiles(files);
    }
  }

  async function doSend() {
    if (streaming) return;
    const ready = attachments.filter((a) => a.path != null);
    const value = text.trim();
    if (!value && ready.length === 0) return;

    // 引用行让 agent 知道去 workspace 读取（约定见 sys-prompt）
    const refs =
      ready.length > 0
        ? `${value ? "\n\n" : ""}附件：${ready
            .map((a) => (a.path ?? "").replace(/^\//, ""))
            .join("、")}`
        : "";
    setText("");
    requestAnimationFrame(autoGrow);
    setAttachments((prev) => prev.filter((a) => a.path == null));
    await send(value + refs);
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
            dragging && "border-ring",
          )}
          onDragOver={(e) => {
            e.preventDefault();
            setDragging(true);
          }}
          onDragLeave={() => setDragging(false)}
          onDrop={(e) => {
            e.preventDefault();
            setDragging(false);
            if (!streaming) pickFiles(Array.from(e.dataTransfer.files));
          }}
        >
          <input
            ref={fileRef}
            type="file"
            multiple
            className="hidden"
            onChange={(e) => {
              if (e.target.files) pickFiles(Array.from(e.target.files));
              e.target.value = "";
            }}
          />
          <Button
            variant="ghost"
            size="icon"
            className="size-8 shrink-0 rounded-lg text-muted-foreground"
            disabled={streaming}
            onClick={() => fileRef.current?.click()}
            title="上传附件（存入工作区）"
          >
            <Paperclip className="size-4" />
          </Button>
          <textarea
            ref={ref}
            rows={1}
            value={text}
            onChange={onChange}
            onKeyDown={onKeyDown}
            onPaste={onPaste}
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
              disabled={!text.trim() && !attachments.some((a) => a.path)}
              onClick={() => void doSend()}
              title="发送"
            >
              <ArrowUp className="size-4" />
            </Button>
          )}
        </div>
        {attachments.length > 0 && (
          <AttachmentChips
            attachments={attachments}
            onRemove={removeAttachment}
          />
        )}
      </div>
    </div>
  );
}

/** 附件行：上传中转圈、成功显示将随消息引用的路径、失败标红可移除后重试。 */
function AttachmentChips({
  attachments,
  onRemove,
}: {
  attachments: readonly Attachment[];
  onRemove: (id: string) => void;
}) {
  return (
    <div className="flex flex-wrap gap-1.5 px-1 pt-1.5">
      {attachments.map((a) => (
        <span
          key={a.id}
          className={cn(
            "inline-flex max-w-56 items-center gap-1.5 rounded-full border border-border bg-secondary px-2.5 py-1 text-xs",
            a.error && "border-destructive/40 bg-destructive/10 text-destructive",
          )}
          title={a.error ?? a.path ?? a.name}
        >
          {a.uploading ? (
            <Loader2 className="size-3 shrink-0 animate-spin" />
          ) : (
            <FileText className="size-3 shrink-0 text-primary" />
          )}
          <span className="truncate">
            {a.name}
            {a.error && `：${a.error}`}
          </span>
          <button
            type="button"
            className="shrink-0 rounded-full p-0.5 hover:bg-accent"
            onClick={() => onRemove(a.id)}
            aria-label="移除附件"
          >
            <X className="size-3" />
          </button>
        </span>
      ))}
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
