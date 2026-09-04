// ui-requests store 单测：push 幂等 / respond HTTP 成败 / clear。

import { beforeEach, describe, expect, it, vi } from "vitest";

// 直接测 store 逻辑：把 api.respondHitl mock 掉。store 模块已捕获真实 import，
// 用 vi.mock 在模块加载前拦截。
vi.mock("@/lib/api", () => ({
  respondHitl: vi.fn(async (_id: string, _action: string) => undefined),
}));

import { respondHitl } from "@/lib/api";
import { useUiRequests } from "./ui-requests";

const mockRespond = vi.mocked(respondHitl);

beforeEach(() => {
  useUiRequests.getState().clear();
  mockRespond.mockClear();
  mockRespond.mockResolvedValue(undefined);
});

describe("useUiRequests", () => {
  it("push 设置 pending；同 id 幂等不覆盖", () => {
    useUiRequests.getState().push({ id: "u1", method: "confirm", title: "A" });
    useUiRequests.getState().push({ id: "u1", method: "confirm", title: "B" });
    expect(useUiRequests.getState().pending?.title).toBe("A");
  });

  it("push 新 id 覆盖旧请求", () => {
    useUiRequests.getState().push({ id: "u1", method: "confirm" });
    useUiRequests.getState().push({ id: "u2", method: "confirm" });
    expect(useUiRequests.getState().pending?.id).toBe("u2");
  });

  it("respond 成功：POST 对齐参数并清浮层", async () => {
    useUiRequests.getState().push({ id: "u1", method: "confirm", title: "执行" });
    await useUiRequests.getState().respond("approve");
    expect(mockRespond).toHaveBeenCalledWith("u1", "approve", undefined);
    expect(useUiRequests.getState().pending).toBeNull();
    expect(useUiRequests.getState().error).toBeNull();
  });

  it("respond 失败：保留请求并置 error", async () => {
    mockRespond.mockRejectedValue(new Error("404 未知确认请求"));
    useUiRequests.getState().push({ id: "u1", method: "confirm" });
    await useUiRequests.getState().respond("reject");
    expect(useUiRequests.getState().pending?.id).toBe("u1");
    expect(useUiRequests.getState().error).toBe("404 未知确认请求");
  });

  it("respond 中防重复点击", async () => {
    let resolveFirst: () => void = () => undefined;
    mockRespond.mockImplementation(
      () =>
        new Promise<void>((r) => {
          resolveFirst = r;
        }),
    );
    useUiRequests.getState().push({ id: "u1", method: "confirm" });
    const first = useUiRequests.getState().respond("approve");
    const second = useUiRequests.getState().respond("approve");
    await second;
    expect(mockRespond).toHaveBeenCalledTimes(1);
    resolveFirst();
    await first;
    expect(useUiRequests.getState().pending).toBeNull();
  });

  it("无 pending 时 respond 不发请求", async () => {
    await useUiRequests.getState().respond("approve");
    expect(mockRespond).not.toHaveBeenCalled();
  });

  it("clear 丢弃浮层", () => {
    useUiRequests.getState().push({ id: "u1", method: "confirm" });
    useUiRequests.getState().clear();
    expect(useUiRequests.getState().pending).toBeNull();
  });
});
