// sse.ts 单测：mock EventSource，验证状态流转与具名事件分发。
// 联调踩坑：后端每帧带 event: 名（= ChatDelta.type()），具名事件不触发
// onmessage——必须 addEventListener 逐名注册。本组测试锁死该行为。

import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { createChatSse, sseUrl } from "./sse";

type NamedListener = (e: { data: string }) => void;

class MockEventSource {
  static instances: MockEventSource[] = [];
  // sse.ts 用 EventSource.CLOSED 静态常量比较，mock 需提供
  static readonly CONNECTING = 0;
  static readonly OPEN = 1;
  static readonly CLOSED = 2;
  readyState = 0; // CONNECTING
  onopen: (() => void) | null = null;
  onmessage: NamedListener | null = null;
  onerror: (() => void) | null = null;
  readonly namedListeners = new Map<string, NamedListener>();
  closed = false;
  readonly url: string;

  constructor(url: string) {
    this.url = url;
    MockEventSource.instances.push(this);
  }

  addEventListener(name: string, listener: NamedListener) {
    this.namedListeners.set(name, listener);
  }

  close() {
    this.closed = true;
    this.readyState = 2;
  }

  // 测试驱动
  open() {
    this.readyState = 1;
    this.onopen?.();
  }
  deliver(data: string) {
    this.onmessage?.({ data });
  }
  /** 模拟后端具名帧：event: <name> + data。 */
  deliverNamed(name: string, data: string) {
    this.namedListeners.get(name)?.({ data });
  }
  fail() {
    this.onerror?.();
  }
}

beforeEach(() => {
  MockEventSource.instances = [];
  vi.stubGlobal("EventSource", MockEventSource);
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("sseUrl", () => {
  it("拼会话事件路由（id 编码）", () => {
    expect(sseUrl("abc")).toBe("/api/sessions/abc/events");
    expect(sseUrl("a/b c")).toBe("/api/sessions/a%2Fb%20c/events");
  });
});

describe("createChatSse", () => {
  it("onopen → open 状态", () => {
    const states: string[] = [];
    const { close } = createChatSse("s1", {
      onDelta: () => undefined,
      onState: (s) => states.push(s),
    });
    const es = MockEventSource.instances[0]!;
    es.open();
    expect(states).toEqual(["open"]);
    close();
  });

  it("全部 14 种 ChatDelta 事件名都已注册监听", () => {
    createChatSse("s1", { onDelta: () => undefined, onState: () => undefined });
    const es = MockEventSource.instances[0]!;
    const names = [
      "session_info", "messages_loaded", "thinking_level", "prompt_accepted",
      "thinking_start", "thinking_delta", "text_delta", "tool_start", "tool_args",
      "tool_end", "message_usage", "ui_request", "agent_end", "error",
    ];
    for (const name of names) {
      expect(es.namedListeners.has(name), `缺事件名监听: ${name}`).toBe(true);
    }
  });

  it("★ 具名帧（后端实际格式）能送达 onDelta——联调踩坑回归", () => {
    const received: unknown[] = [];
    createChatSse("s1", {
      onDelta: (d) => received.push(d),
      onState: () => undefined,
    });
    const es = MockEventSource.instances[0]!;
    es.deliverNamed("session_info", '{"type":"session_info","sessionName":"1"}');
    es.deliverNamed("text_delta", '{"type":"text_delta","text":"hi"}');
    es.deliverNamed("agent_end", '{"type":"agent_end"}');
    expect(received).toEqual([
      { type: "session_info", sessionName: "1" },
      { type: "text_delta", text: "hi" },
      { type: "agent_end" },
    ]);
  });

  it("无名帧（兜底 onmessage）也能送达", () => {
    const received: unknown[] = [];
    createChatSse("s1", {
      onDelta: (d) => received.push(d),
      onState: () => undefined,
    });
    MockEventSource.instances[0]!.deliver('{"type":"text_delta","text":"hi"}');
    expect(received).toEqual([{ type: "text_delta", text: "hi" }]);
  });

  it("非 JSON data 静默忽略", () => {
    const received: unknown[] = [];
    createChatSse("s1", {
      onDelta: (d) => received.push(d),
      onState: () => undefined,
    });
    const es = MockEventSource.instances[0]!;
    es.deliverNamed("text_delta", "not json");
    expect(received).toHaveLength(0);
  });

  it("onerror 自动重连（CONNECTING）报 connecting，CLOSED 报 closed", () => {
    const states: string[] = [];
    createChatSse("s1", {
      onDelta: () => undefined,
      onState: (s) => states.push(s),
    });
    const es = MockEventSource.instances[0]!;
    es.open();
    es.readyState = 0; // 断线后自动重连中
    es.fail();
    es.readyState = 2; // 服务端彻底关闭
    es.fail();
    expect(states).toEqual(["open", "connecting", "closed"]);
  });

  it("close() 断开不再收消息", () => {
    const received: unknown[] = [];
    const sse = createChatSse("s1", {
      onDelta: (d) => received.push(d),
      onState: () => undefined,
    });
    const es = MockEventSource.instances[0]!;
    sse.close();
    es.open();
    es.deliverNamed("agent_end", '{"type":"agent_end"}');
    expect(es.closed).toBe(true);
    expect(received).toHaveLength(0);
  });
});
