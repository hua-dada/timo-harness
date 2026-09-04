// 右侧资源面板：Tabs(workspace 树+编辑器 | preview) + 上传按钮。
// preview：图片直显、html/iframe、其他文本 inline 读出。

import { useEffect, useState } from "react";
import { FolderTree, Eye, Upload, ExternalLink } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { Tip } from "@/components/ui/tooltip";
import { cn } from "@/lib/utils";
import { previewUrl, readFileContent, uploadFile } from "@/lib/api";
import { FileTree, TreeRefreshButton, bumpFileTree } from "./FileTree";
import { FileEditor } from "./FileEditor";
import { useChatPanelOpen } from "@/store/panel";

const IMAGE_EXT = /\.(png|jpe?g|gif|webp|svg|bmp|ico)$/i;
const HTML_EXT = /\.x?html?$/i;
const TEXT_PREVIEW_MAX = 128 * 1024;

export function ResourcePanel({ className }: { className?: string }) {
  const open = useChatPanelOpen();
  const [selectedPath, setSelectedPath] = useState<string | null>(null);
  const [tab, setTab] = useState("workspace");
  const [uploading, setUploading] = useState(false);
  const [uploadError, setUploadError] = useState<string | null>(null);

  function onSelect(path: string) {
    setSelectedPath(path);
    // 图片/html → preview 感兴趣；其余文本留在 workspace 编辑
    if (IMAGE_EXT.test(path) || HTML_EXT.test(path)) setTab("preview");
  }

  async function onUpload(e: React.ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0];
    e.target.value = "";
    if (!file) return;
    setUploading(true);
    setUploadError(null);
    try {
      await uploadFile(file);
      bumpFileTree();
    } catch (err) {
      setUploadError(err instanceof Error ? err.message : "上传失败");
    } finally {
      setUploading(false);
    }
  }

  if (!open) return null;

  return (
    <aside className={cn("paper-texture hidden w-80 shrink-0 flex-col border-l border-border bg-card xl:flex", className)}>
      <Tabs value={tab} onValueChange={setTab} className="flex min-h-0 flex-1 flex-col">
        <div className="flex h-12 shrink-0 items-center gap-2 border-b border-border px-3">
          <TabsList className="h-8">
            <TabsTrigger value="workspace" className="gap-1.5 px-2.5 text-xs">
              <FolderTree className="size-3.5" />
              工作区
            </TabsTrigger>
            <TabsTrigger value="preview" className="gap-1.5 px-2.5 text-xs">
              <Eye className="size-3.5" />
              预览
            </TabsTrigger>
          </TabsList>
          <span className="ml-auto flex items-center gap-1">
            <TreeRefreshButton />
            <label className="relative inline-flex cursor-pointer">
              <input type="file" className="hidden" onChange={(e) => void onUpload(e)} />
              <Tip label="上传到工作区">
                <Button variant="ghost" size="icon" className="pointer-events-none size-7" disabled={uploading}>
                  <Upload className="size-3.5" />
                </Button>
              </Tip>
            </label>
          </span>
        </div>
        {uploadError && <p className="px-3 py-1.5 text-xs text-destructive">{uploadError}</p>}
        <TabsContent value="workspace" className="flex min-h-0 flex-1 flex-col">
          <div className="scroll-subtle max-h-64 shrink-0 overflow-y-auto border-b border-border">
            <FileTree onSelect={onSelect} selectedPath={selectedPath} />
          </div>
          {selectedPath && !IMAGE_EXT.test(selectedPath) && !HTML_EXT.test(selectedPath) ? (
            <FileEditor path={selectedPath} onClose={() => setSelectedPath(null)} />
          ) : (
            <EmptyPane text={selectedPath ?? "选择左侧文件查看 / 编辑"} />
          )}
        </TabsContent>
        <TabsContent value="preview" className="flex min-h-0 flex-1 flex-col">
          <FilePreview path={selectedPath} />
        </TabsContent>
      </Tabs>
    </aside>
  );
}

function EmptyPane({ text }: { text: string }) {
  return (
    <div className="flex flex-1 items-center justify-center p-4 text-center text-xs text-muted-foreground">
      {text}
    </div>
  );
}

function FilePreview({ path }: { path: string | null }) {
  if (!path) return <EmptyPane text="在「工作区」选择文件后此处预览" />;

  if (IMAGE_EXT.test(path)) {
    return (
      <div className="scroll-subtle flex-1 overflow-auto p-3">
        <img src={previewUrl(path)} alt={path} className="max-w-full rounded-md border border-border" />
      </div>
    );
  }
  if (HTML_EXT.test(path)) {
    return (
      <div className="relative flex-1">
        <a
          href={previewUrl(path)}
          target="_blank"
          rel="noreferrer"
          className="absolute top-2 right-2 z-10 inline-flex items-center gap-1 rounded-md bg-background/80 px-2 py-1 text-xs text-muted-foreground backdrop-blur hover:text-foreground"
        >
          新窗口打开 <ExternalLink className="size-3" />
        </a>
        <iframe src={previewUrl(path)} title={path} className="size-full border-0 bg-background" sandbox="allow-same-origin" />
      </div>
    );
  }

  return <TextPreview path={path} />;
}

function TextPreview({ path }: { path: string }) {
  const [text, setText] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    setText(null);
    setError(null);
    readFileContent(path)
      .then((content) => {
        if (cancelled) return;
        setText(
          content.length > TEXT_PREVIEW_MAX
            ? `${content.slice(0, TEXT_PREVIEW_MAX)}\n…（截断）`
            : content,
        );
      })
      .catch((e) => {
        if (!cancelled) setError(e instanceof Error ? e.message : "读取失败");
      });
    return () => {
      cancelled = true;
    };
  }, [path]);

  if (error) return <p className="p-3 text-xs text-destructive">{error}</p>;
  if (text == null) return <EmptyPane text="读取中…" />;
  return (
    <pre className="scroll-subtle flex-1 overflow-auto p-3 font-mono text-[11px] leading-relaxed whitespace-pre-wrap text-foreground/80">
      {text}
    </pre>
  );
}
