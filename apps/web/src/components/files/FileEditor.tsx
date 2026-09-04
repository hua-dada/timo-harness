// 文件编辑器：读取 → 编辑（dirty 圆点）→ 保存（PUT）。只编辑文本文件；
// 二进制/未知扩展名由父面板转 preview。

import { useEffect, useRef, useState } from "react";
import { Save, Loader2 } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Tip } from "@/components/ui/tooltip";
import { cn } from "@/lib/utils";
import { readFileContent, writeFileContent } from "@/lib/api";
import { bumpFileTree } from "./FileTree";

const MAX_EDIT_BYTES = 256 * 1024;

export function FileEditor({ path, onClose }: { path: string; onClose: () => void }) {
  const [saved, setSaved] = useState<string | null>(null);
  const [draft, setDraft] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const pathRef = useRef(path);

  useEffect(() => {
    // 切文件：重置状态再读
    pathRef.current = path;
    setSaved(null);
    setDraft(null);
    setError(null);
    setLoading(true);
    let cancelled = false;
    readFileContent(path)
      .then((content) => {
        if (cancelled || pathRef.current !== path) return;
        if (content.length > MAX_EDIT_BYTES) {
          setError("文件超过 256 KB，不支持在线编辑，请用预览查看");
          setLoading(false);
          return;
        }
        setSaved(content);
        setDraft(content);
        setLoading(false);
      })
      .catch((e) => {
        if (cancelled) return;
        setError(e instanceof Error ? e.message : "读文件失败");
        setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [path]);

  const dirty = draft != null && saved != null && draft !== saved;

  async function save() {
    if (draft == null || saving) return;
    setSaving(true);
    setError(null);
    try {
      await writeFileContent(path, draft);
      setSaved(draft);
      bumpFileTree();
    } catch (e) {
      setError(e instanceof Error ? e.message : "保存失败");
    } finally {
      setSaving(false);
    }
  }

  return (
    <div className="flex min-h-0 flex-1 flex-col">
      <div className="flex h-10 shrink-0 items-center gap-1.5 border-b border-border px-3">
        <span className="truncate font-mono text-xs text-foreground/80">{path}</span>
        {dirty && <span className="size-1.5 shrink-0 rounded-full bg-[#a8946a]" title="未保存" />}
        <span className="ml-auto flex items-center gap-1">
          <Tip label="保存（工作区直写）">
            <Button
              variant="ghost"
              size="icon"
              className="size-7"
              disabled={!dirty || saving}
              onClick={() => void save()}
            >
              {saving ? (
                <Loader2 className="size-3.5 animate-spin" />
              ) : (
                <Save className="size-3.5" />
              )}
            </Button>
          </Tip>
          <Button variant="ghost" size="sm" className="h-7 px-2 text-xs" onClick={onClose}>
            关闭
          </Button>
        </span>
      </div>
      {error && (
        <p className="px-3 py-1.5 text-xs text-destructive">{error}</p>
      )}
      {loading ? (
        <div className="flex flex-1 items-center justify-center text-muted-foreground">
          <Loader2 className="size-4 animate-spin" />
        </div>
      ) : (
        <textarea
          value={draft ?? ""}
          onChange={(e) => setDraft(e.target.value)}
          onKeyDown={(e) => {
            if ((e.ctrlKey || e.metaKey) && e.key === "s") {
              e.preventDefault();
              void save();
            }
          }}
          spellCheck={false}
          className={cn(
            "scroll-subtle flex-1 resize-none bg-transparent p-3 font-mono text-xs leading-relaxed text-foreground/90 outline-none",
          )}
        />
      )}
    </div>
  );
}
