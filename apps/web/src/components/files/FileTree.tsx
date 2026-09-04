// 文件树：懒展开递归目录。点击文件 → 通知 onSelect（父面板开编辑器/预览）。
// 删除走文件行尾 hover 按钮 + Dialog 确认（不做右键菜单——M1 精简）。

import { memo, useEffect, useState } from "react";
import { ChevronRight, File as FileIcon, Folder, FolderOpen, Trash2, Loader2, RefreshCw } from "lucide-react";
import { create } from "zustand";
import { Button } from "@/components/ui/button";
import { Tip } from "@/components/ui/tooltip";
import { cn } from "@/lib/utils";
import { deleteFile, listFiles } from "@/lib/api";

export interface FileTreeProps {
  readonly onSelect: (path: string) => void;
  readonly selectedPath: string | null;
}

interface Node {
  readonly name: string;
  readonly path: string;
  readonly type: "file" | "dir";
}

export const FileTree = memo(function FileTree({ onSelect, selectedPath }: FileTreeProps) {
  const [root, setRoot] = useState<Node[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [confirming, setConfirming] = useState<Node | null>(null);
  const refreshToken = useFileTreeRefresh();

  useEffect(() => {
    let cancelled = false;
    listFiles("")
      .then((entries) => {
        if (!cancelled) {
          setRoot(entries.map(toNode("")).sort(dirsFirst));
          setError(null);
        }
      })
      .catch((e) => {
        if (!cancelled) setError(e instanceof Error ? e.message : "列目录失败");
      });
    return () => {
      cancelled = true;
    };
  }, [refreshToken]);

  if (error) {
    return <p className="px-3 py-2 text-xs text-destructive">{error}</p>;
  }
  if (!root) {
    return (
      <div className="flex justify-center px-3 py-4 text-muted-foreground">
        <Loader2 className="size-4 animate-spin" />
      </div>
    );
  }
  if (root.length === 0) {
    return <p className="px-3 py-4 text-xs text-muted-foreground">工作区为空</p>;
  }

  return (
    <div className="p-1 font-mono text-xs">
      <ul>
        {root.map((n) => (
          <li key={n.path}>
            <Row
              node={n}
              depth={0}
              selectedPath={selectedPath}
              onSelect={onSelect}
              onDelete={setConfirming}
            />
          </li>
        ))}
      </ul>
      {confirming && (
        <ConfirmDelete
          node={confirming}
          onClose={() => setConfirming(null)}
          onDone={() => {
            setConfirming(null);
            bumpFileTree();
          }}
        />
      )}
    </div>
  );
});

function toNode(parent: string) {
  return (e: { name: string; type: "file" | "dir" }): Node => ({
    name: e.name,
    path: parent ? `${parent}/${e.name}` : e.name,
    type: e.type,
  });
}

function dirsFirst(a: Node, b: Node): number {
  if (a.type !== b.type) return a.type === "dir" ? -1 : 1;
  return a.name.localeCompare(b.name);
}

function Row({
  node,
  depth,
  selectedPath,
  onSelect,
  onDelete,
}: {
  node: Node;
  depth: number;
  selectedPath: string | null;
  onSelect: (path: string) => void;
  onDelete: (node: Node) => void;
}) {
  if (node.type === "dir") {
    return <DirNode node={node} depth={depth} selectedPath={selectedPath} onSelect={onSelect} onDelete={onDelete} />;
  }
  const selected = selectedPath === node.path;
  return (
    <div
      className={cn(
        "group flex cursor-pointer items-center gap-1 rounded-sm pr-1 transition-colors hover:bg-accent/50",
        selected && "bg-accent text-accent-foreground",
      )}
      style={{ paddingLeft: depth * 12 + 4 }}
      onClick={() => onSelect(node.path)}
    >
      <FileIcon className="size-3.5 shrink-0 text-muted-foreground" />
      <span className="truncate py-1.5">{node.name}</span>
      <button
        className="ml-auto hidden shrink-0 rounded p-0.5 text-muted-foreground hover:text-destructive group-hover:block"
        onClick={(e) => {
          e.stopPropagation();
          onDelete(node);
        }}
        title="删除"
      >
        <Trash2 className="size-3" />
      </button>
    </div>
  );
}

function DirNode({
  node,
  depth,
  selectedPath,
  onSelect,
  onDelete,
}: {
  node: Node;
  depth: number;
  selectedPath: string | null;
  onSelect: (path: string) => void;
  onDelete: (node: Node) => void;
}) {
  const [open, setOpen] = useState(false);
  const [children, setChildren] = useState<Node[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  function toggle() {
    const next = !open;
    setOpen(next);
    if (next && children == null) {
      listFiles(node.path)
        .then((entries) => setChildren(entries.map(toNode(node.path)).sort(dirsFirst)))
        .catch((e) => setError(e instanceof Error ? e.message : "列目录失败"));
    }
  }

  return (
    <>
      <div
        className="group flex cursor-pointer items-center gap-1 rounded-sm pr-1 transition-colors hover:bg-accent/50"
        style={{ paddingLeft: depth * 12 + 4 }}
        onClick={toggle}
      >
        <ChevronRight className={cn("size-3.5 shrink-0 text-muted-foreground transition-transform", open && "rotate-90")} />
        {open ? (
          <FolderOpen className="size-3.5 shrink-0 text-muted-foreground" />
        ) : (
          <Folder className="size-3.5 shrink-0 text-muted-foreground" />
        )}
        <span className="truncate py-1.5">{node.name}</span>
        <button
          className="ml-auto hidden shrink-0 rounded p-0.5 text-muted-foreground hover:text-destructive group-hover:block"
          onClick={(e) => {
            e.stopPropagation();
            onDelete(node);
          }}
          title="删除"
        >
          <Trash2 className="size-3" />
        </button>
      </div>
      {open && (
        <div>
          {error && <p className="py-1 pl-8 text-destructive">{error}</p>}
          {!error && children == null && (
            <div className="flex justify-center py-1 pl-8 text-muted-foreground">
              <Loader2 className="size-3 animate-spin" />
            </div>
          )}
          {children?.length === 0 && <p className="py-1 pl-8 text-muted-foreground">（空）</p>}
          {children?.map((c) => (
            <Row
              key={c.path}
              node={c}
              depth={depth + 1}
              selectedPath={selectedPath}
              onSelect={onSelect}
              onDelete={onDelete}
            />
          ))}
        </div>
      )}
    </>
  );
}

function ConfirmDelete({
  node,
  onClose,
  onDone,
}: {
  node: Node;
  onClose: () => void;
  onDone: () => void;
}) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function remove() {
    setBusy(true);
    try {
      await deleteFile(node.path);
      onDone();
    } catch (e) {
      setError(e instanceof Error ? e.message : "删除失败");
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60" onClick={onClose}>
      <div
        className="w-full max-w-sm rounded-xl border border-border bg-card p-5 shadow-lg"
        onClick={(e) => e.stopPropagation()}
      >
        <h3 className="text-sm font-semibold">确认删除</h3>
        <p className="mt-1.5 text-xs break-all text-muted-foreground">
          {node.type === "dir" ? "目录" : "文件"} <span className="font-mono">{node.path}</span>{" "}
          将从工作区删除{node.type === "dir" ? "（含全部内容）" : ""}，不可恢复。
        </p>
        {error && <p className="mt-2 text-xs text-destructive">{error}</p>}
        <div className="mt-4 flex justify-end gap-2">
          <Button size="sm" variant="ghost" disabled={busy} onClick={onClose}>
            取消
          </Button>
          <Button size="sm" variant="destructive" disabled={busy} onClick={() => void remove()}>
            删除
          </Button>
        </div>
      </div>
    </div>
  );
}

/** 树刷新信号（上传/保存/删除后 bump，FileTree 按 token 重列）。 */
const refreshStore = create<{ token: number }>(() => ({ token: 0 }));
export function useFileTreeRefresh(): number {
  return refreshStore((s) => s.token);
}
export function bumpFileTree(): void {
  refreshStore.setState((s) => ({ token: s.token + 1 }));
}

export function TreeRefreshButton() {
  return (
    <Tip label="刷新目录">
      <Button
        variant="ghost"
        size="icon"
        className="size-7"
        onClick={() => bumpFileTree()}
      >
        <RefreshCw className="size-3.5" />
      </Button>
    </Tip>
  );
}
