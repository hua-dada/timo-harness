// api.ts 单测：mock fetch，验证 URL/方法/错误体解析。

import { afterEach, describe, expect, it, vi } from "vitest";
import {
  fetchMe,
  login,
  createSession,
  listSessions,
  sendMessage,
  abortSession,
  respondHitl,
  listFiles,
  readFileContent,
  writeFileContent,
  deleteFile,
  uploadFile,
  previewUrl,
} from "./api";

function mockFetch(status: number, body: unknown) {
  return vi.fn<typeof fetch>(
    async () =>
      new Response(JSON.stringify(body), {
        status,
        headers: { "Content-Type": "application/json" },
      }),
  );
}

type FetchMock = ReturnType<typeof mockFetch>;

/** 取第 i 次调用的 (url, init)；init 缺省为空对象（简化断言）。 */
function nthCall(f: FetchMock, i = 0): { url: string; init: RequestInit } {
  const call = f.mock.calls[i];
  if (!call) throw new Error(`fetch 未被调用（第 ${i} 次）`);
  return { url: String(call[0]), init: call[1] ?? {} };
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("认证", () => {
  it("fetchMe 解析 me", async () => {
    const f = mockFetch(200, { userId: "u1", username: "timo", role: "member" });
    vi.stubGlobal("fetch", f);
    const me = await fetchMe();
    expect(me.username).toBe("timo");
    expect(f).toHaveBeenCalledWith("/api/auth/me", { credentials: "same-origin" });
  });

  it("login POST JSON", async () => {
    const f = mockFetch(200, { userId: "u1", username: "timo", role: "member" });
    vi.stubGlobal("fetch", f);
    await login("timo", "pw");
    const { url, init } = nthCall(f);
    expect(url).toBe("/api/auth/login");
    expect(init.method).toBe("POST");
    expect(JSON.parse(String(init.body))).toEqual({ username: "timo", password: "pw" });
  });

  it("401 {error} → throw Error(error 文本)", async () => {
    vi.stubGlobal("fetch", mockFetch(401, { error: "用户名或密码错误" }));
    await expect(login("timo", "bad")).rejects.toThrow("用户名或密码错误");
  });

  it("非 JSON 错误体 → fallback 文本", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(async () => new Response("<html>502</html>", { status: 502 })),
    );
    await expect(fetchMe()).rejects.toThrow("获取登录态失败");
  });
});

describe("会话", () => {
  it("listSessions 取 sessions 数组", async () => {
    vi.stubGlobal(
      "fetch",
      mockFetch(200, { sessions: [{ sessionId: "s1", name: "a", state: "idle", createdAt: 1 }] }),
    );
    const list = await listSessions();
    expect(list).toHaveLength(1);
    expect(list[0]?.sessionId).toBe("s1");
  });

  it("createSession 空 name 发 null", async () => {
    const f = mockFetch(200, { sessionId: "s2", name: "新会话", state: "idle", createdAt: 2 });
    vi.stubGlobal("fetch", f);
    await createSession();
    const { init } = nthCall(f);
    expect(JSON.parse(String(init.body))).toEqual({ name: null });
  });

  it("sendMessage 409 busy → throw error 文本", async () => {
    vi.stubGlobal("fetch", mockFetch(409, { error: "会话正在处理上一条消息" }));
    await expect(sendMessage("s1", "hi")).rejects.toThrow("会话正在处理上一条消息");
  });

  it("abortSession POST abort 路由", async () => {
    const f = mockFetch(200, { ok: true });
    vi.stubGlobal("fetch", f);
    await abortSession("s1");
    const { url, init } = nthCall(f);
    expect(url).toBe("/api/sessions/s1/abort");
    expect(init.method).toBe("POST");
  });
});

describe("HITL", () => {
  it("respondHitl 三参形状", async () => {
    const f = mockFetch(200, { ok: true });
    vi.stubGlobal("fetch", f);
    await respondHitl("req-1", "modify", { command: "ls" });
    const { url, init } = nthCall(f);
    expect(url).toBe("/api/hitl/req-1");
    expect(JSON.parse(String(init.body))).toEqual({ action: "modify", args: { command: "ls" } });
  });
});

describe("文件", () => {
  it("listFiles 编码 path", async () => {
    const f = mockFetch(200, { entries: [{ name: "a.txt", type: "file" }] });
    vi.stubGlobal("fetch", f);
    const entries = await listFiles("sub/dir");
    expect(entries[0]?.name).toBe("a.txt");
    expect(nthCall(f).url).toBe(
      "/files/list?path=sub%2Fdir",
    );
  });

  it("readFileContent / writeFileContent", async () => {
    vi.stubGlobal("fetch", mockFetch(200, { content: "hello" }));
    expect(await readFileContent("a.txt")).toBe("hello");

    const f = mockFetch(200, { path: "a.txt", ok: true });
    vi.stubGlobal("fetch", f);
    await writeFileContent("a.txt", "new");
    const { init } = nthCall(f);
    expect(init.method).toBe("PUT");
    expect(JSON.parse(String(init.body))).toEqual({ path: "a.txt", content: "new" });
  });

  it("deleteFile DELETE", async () => {
    const f = mockFetch(200, { ok: true });
    vi.stubGlobal("fetch", f);
    await deleteFile("a.txt");
    const { url, init } = nthCall(f);
    expect(url).toBe("/files/content?path=a.txt");
    expect(init.method).toBe("DELETE");
  });

  it("uploadFile multipart 带 file 字段", async () => {
    const f = mockFetch(200, { path: "up.bin" });
    vi.stubGlobal("fetch", f);
    const path = await uploadFile(new File([new Uint8Array([1])], "up.bin"));
    expect(path).toBe("up.bin");
    const { init } = nthCall(f);
    expect(init.body).toBeInstanceOf(FormData);
    expect((init.body as FormData).get("file")).toBeInstanceOf(File);
  });
});

describe("previewUrl", () => {
  it("path-style 且剥前导斜杠", () => {
    expect(previewUrl("a/b.html")).toBe("/files/preview/a/b.html");
    expect(previewUrl("/x.png")).toBe("/files/preview/x.png");
    expect(previewUrl("子 目录/a.png")).toBe("/files/preview/%E5%AD%90%20%E7%9B%AE%E5%BD%95/a.png");
  });
});
