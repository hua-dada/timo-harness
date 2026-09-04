// HTTP 层：裸 fetch + credentials:"same-origin"（cookie 自动带），错误统一
// throw new Error(body.error ?? fallback)——对齐本仓后端 {"error":"…"} 错误体。

export interface Me {
  readonly userId: string;
  readonly username: string;
  readonly realName?: string;
  readonly role: "admin" | "member";
}

export interface SessionMeta {
  readonly sessionId: string;
  readonly name: string;
  readonly state: string;
  readonly createdAt: number;
}

async function jsonOrError<T>(resp: Response, fallback: string): Promise<T> {
  let body: unknown;
  try {
    body = await resp.json();
  } catch {
    body = null;
  }
  if (!resp.ok) {
    const message =
      body && typeof body === "object" && typeof (body as { error?: unknown }).error === "string"
        ? (body as { error: string }).error
        : fallback;
    throw new Error(message);
  }
  return body as T;
}

// ── 认证 ───────────────────────────────────────────────────────────────────

export async function fetchMe(): Promise<Me> {
  const resp = await fetch("/api/auth/me", { credentials: "same-origin" });
  return jsonOrError(resp, "获取登录态失败");
}

export async function login(username: string, password: string): Promise<Me> {
  const resp = await fetch("/api/auth/login", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    credentials: "same-origin",
    body: JSON.stringify({ username, password }),
  });
  return jsonOrError(resp, "登录失败");
}

export async function logout(): Promise<void> {
  const resp = await fetch("/api/auth/logout", {
    method: "POST",
    credentials: "same-origin",
  });
  if (!resp.ok) throw new Error("登出失败");
}

// ── 会话 ───────────────────────────────────────────────────────────────────

export async function listSessions(): Promise<SessionMeta[]> {
  const resp = await fetch("/api/sessions", { credentials: "same-origin" });
  const body = await jsonOrError<{ sessions: SessionMeta[] }>(resp, "获取会话列表失败");
  return body.sessions ?? [];
}

export async function createSession(name?: string): Promise<SessionMeta> {
  const resp = await fetch("/api/sessions", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    credentials: "same-origin",
    body: JSON.stringify({ name: name ?? null }),
  });
  return jsonOrError(resp, "创建会话失败");
}

export async function sendMessage(sessionId: string, message: string): Promise<void> {
  const resp = await fetch(`/api/sessions/${encodeURIComponent(sessionId)}/messages`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    credentials: "same-origin",
    body: JSON.stringify({ message }),
  });
  // 409 busy（会话在跑/等确认）等一律经 error 文本透出，UI 提示不重试
  await jsonOrError(resp, "消息发送失败");
}

export async function abortSession(sessionId: string): Promise<void> {
  const resp = await fetch(`/api/sessions/${encodeURIComponent(sessionId)}/abort`, {
    method: "POST",
    credentials: "same-origin",
  });
  await jsonOrError(resp, "中止失败");
}

// ── HITL 应答 ──────────────────────────────────────────────────────────────

export type HitlAction = "approve" | "reject" | "modify";

export async function respondHitl(
  requestId: string,
  action: HitlAction,
  args?: Record<string, unknown>,
): Promise<void> {
  const resp = await fetch(`/api/hitl/${encodeURIComponent(requestId)}`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    credentials: "same-origin",
    body: JSON.stringify({ action, args }),
  });
  await jsonOrError(resp, "确认应答失败");
}

// ── 文件（/files 短前缀，后端双前缀等价）────────────────────────────────────

export interface FileEntry {
  readonly name: string;
  readonly type: "file" | "dir";
}

export async function listFiles(path = ""): Promise<FileEntry[]> {
  const resp = await fetch(
    `/files/list?path=${encodeURIComponent(path)}`,
    { credentials: "same-origin" },
  );
  const body = await jsonOrError<{ entries: FileEntry[] }>(resp, "列目录失败");
  return body.entries ?? [];
}

export async function readFileContent(path: string): Promise<string> {
  const resp = await fetch(
    `/files/content?path=${encodeURIComponent(path)}`,
    { credentials: "same-origin" },
  );
  const body = await jsonOrError<{ content: string }>(resp, "读文件失败");
  return body.content ?? "";
}

export async function writeFileContent(path: string, content: string): Promise<void> {
  const resp = await fetch("/files/content", {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    credentials: "same-origin",
    body: JSON.stringify({ path, content }),
  });
  await jsonOrError(resp, "写文件失败");
}

export async function deleteFile(path: string): Promise<void> {
  const resp = await fetch(
    `/files/content?path=${encodeURIComponent(path)}`,
    { method: "DELETE", credentials: "same-origin" },
  );
  await jsonOrError(resp, "删除失败");
}

export async function uploadFile(file: File): Promise<string> {
  const form = new FormData();
  form.append("file", file);
  const resp = await fetch("/files/upload", {
    method: "POST",
    credentials: "same-origin",
    body: form,
  });
  const body = await jsonOrError<{ path: string }>(resp, "上传失败");
  return body.path;
}

export async function clearWorkspace(): Promise<void> {
  const resp = await fetch("/files/workspace", {
    method: "DELETE",
    credentials: "same-origin",
  });
  await jsonOrError(resp, "清空工作区失败");
}

/** 预览 URL（path-style，iframe 内相对资源正确解析为 /files/preview/…）。 */
export function previewUrl(relPath: string): string {
  return `/files/preview/${encodeURI(relPath.replace(/^\/+/, ""))}`;
}
