// messages-history 单测：pi 形状映射 + toolCallId 配对回填 + 孤儿容错。

import { describe, expect, it } from "vitest";
import { mapHistory, textOf } from "./messages-history";

describe("textOf", () => {
  it("string 直取", () => {
    expect(textOf("hi")).toBe("hi");
  });
  it("块数组拼接 text", () => {
    expect(
      textOf([{ type: "text", text: "a" }, { type: "thinking", thinking: "…" }, { type: "text", text: "b" }]),
    ).toBe("ab");
  });
  it("null/undefined 空", () => {
    expect(textOf(null)).toBe("");
    expect(textOf(undefined)).toBe("");
  });
});

describe("mapHistory", () => {
  it("user 行独立成消息", () => {
    const out = mapHistory([{ role: "user", content: "你好" }]);
    expect(out).toHaveLength(1);
    expect(out[0]?.role).toBe("user");
    if (out[0]?.blocks[0]?.type === "text") expect(out[0].blocks[0].text).toBe("你好");
  });

  it("assistant 行 text/thinking/toolCall 三类块", () => {
    const out = mapHistory([
      {
        role: "assistant",
        content: [
          { type: "text", text: "开跑" },
          { type: "thinking", thinking: "想想" },
          { type: "toolCall", id: "t1", name: "bash", arguments: { cmd: "ls" } },
        ],
        usage: { totalTokens: 42 },
        stopReason: "toolUse",
      },
    ]);
    expect(out).toHaveLength(1);
    const blocks = out[0]?.blocks ?? [];
    expect(blocks.map((b) => b.type)).toEqual(["text", "thinking", "tool"]);
    expect(out[0]?.usageTotal).toBe(42);
    expect(out[0]?.stopReason).toBe("toolUse");
  });

  it("toolResult 行按 toolCallId 回填本回合工具块", () => {
    const out = mapHistory([
      {
        role: "assistant",
        content: [{ type: "toolCall", id: "t1", name: "bash", arguments: { cmd: "ls" } }],
      },
      { role: "toolResult", toolCallId: "t1", isError: false, content: "file1\nfile2" },
    ]);
    expect(out).toHaveLength(1);
    const b = out[0]?.blocks[0];
    if (b?.type === "tool") {
      expect(b.toolCall.status).toBe("done");
      expect(b.toolCall.result).toBe("file1\nfile2");
    }
  });

  it("toolResult isError=true → error 状态", () => {
    const out = mapHistory([
      { role: "assistant", content: [{ type: "toolCall", id: "t1", name: "bash" }] },
      { role: "toolResult", toolCallId: "t1", isError: true, content: "exit 1" },
    ]);
    const b = out[0]?.blocks[0];
    if (b?.type === "tool") expect(b.toolCall.status).toBe("error");
  });

  it("孤儿 toolResult（无对应 assistant 块）被丢弃", () => {
    const out = mapHistory([
      { role: "toolResult", toolCallId: "ghost", isError: false, content: "?" },
    ]);
    expect(out).toHaveLength(0);
  });

  it("跨回合 toolCallId 不串扰（user 边界清空 openToolBlocks）", () => {
    const out = mapHistory([
      { role: "assistant", content: [{ type: "toolCall", id: "t1", name: "bash" }] },
      { role: "user", content: "下一问" },
      { role: "toolResult", toolCallId: "t1", isError: false, content: "迟到结果" },
    ]);
    // 迟到的 t1 结果不该回填到第一回合（user 已边界化）……但 openToolBlocks 未清时
    // 依然指向旧 Map 对象且消息尾部是 user——本实现丢弃。
    expect(out).toHaveLength(2);
    expect(out[1]?.role).toBe("user");
  });

  it("content 非数组/null 行安全跳过", () => {
    const out = mapHistory([
      { role: "assistant", content: null },
      { role: "assistant", content: "纯字符串 content" },
      {},
    ]);
    // 纯字符串 content：Array.isArray 为 false → 块为空，消息仍建立
    expect(out).toHaveLength(2);
    expect(out[0]?.blocks).toHaveLength(0);
  });

  it("完整多回合混合序列", () => {
    const out = mapHistory([
      { role: "user", content: "列目录" },
      {
        role: "assistant",
        content: [
          { type: "thinking", thinking: "用 bash" },
          { type: "toolCall", id: "t1", name: "bash", arguments: { command: "ls" } },
        ],
      },
      { role: "toolResult", toolCallId: "t1", isError: false, content: "a.txt" },
      {
        role: "assistant",
        content: [{ type: "text", text: "共 1 个文件" }],
        usage: { totalTokens: 100 },
        stopReason: "stop",
      },
    ]);
    expect(out.map((m) => m.role)).toEqual(["user", "assistant", "assistant"]);
    const b0 = out[1]?.blocks[0];
    if (b0?.type === "tool") expect(b0.toolCall.status).toBe("done");
    expect(out[2]?.usageTotal).toBe(100);
  });
});
