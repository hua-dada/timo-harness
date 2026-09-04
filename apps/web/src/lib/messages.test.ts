// messages 域模型单测：groupBlocks 分组/合并/噪声吞噬 + parseDelta。

import { describe, expect, it } from "vitest";
import { groupBlocks, ELLIPSIS_NOISE, type MessageBlock } from "./messages";
import { parseDelta } from "./chat-delta-protocol";

function blocksOf(...items: unknown[]) {
  return items as readonly MessageBlock[];
}

describe("groupBlocks", () => {
  it("连续 tool 合并为一组", () => {
    const groups = groupBlocks(blocksOf(
      { type: "tool", id: "t1", toolCall: { id: "t1", name: "a", status: "done" } },
      { type: "tool", id: "t2", toolCall: { id: "t2", name: "b", status: "done" } },
    ));
    expect(groups).toHaveLength(1);
    if (groups[0]?.type === "tools") expect(groups[0].calls).toHaveLength(2);
  });

  it("text/tool/text 三组穿插不合并", () => {
    const groups = groupBlocks(blocksOf(
      { type: "text", id: "b1", text: "先" },
      { type: "tool", id: "t1", toolCall: { id: "t1", name: "a", status: "done" } },
      { type: "text", id: "b2", text: "后" },
    ));
    expect(groups.map((g) => g.type)).toEqual(["text", "tools", "text"]);
  });

  it("省略号噪声文本被吞噬", () => {
    const groups = groupBlocks(blocksOf(
      { type: "tool", id: "t1", toolCall: { id: "t1", name: "a", status: "done" } },
      { type: "text", id: "b1", text: "..." },
      { type: "tool", id: "t2", toolCall: { id: "t2", name: "b", status: "done" } },
    ));
    // 噪声被吞 → 两段 tool 合并为一组
    expect(groups).toHaveLength(1);
    if (groups[0]?.type === "tools") expect(groups[0].calls).toHaveLength(2);
  });

  it("thinking 独立成组且不与 tool 合并", () => {
    const groups = groupBlocks(blocksOf(
      { type: "thinking", id: "k1", text: "想", startedAt: 0, endedAt: 1 },
      { type: "tool", id: "t1", toolCall: { id: "t1", name: "a", status: "done" } },
    ));
    expect(groups.map((g) => g.type)).toEqual(["thinking", "tools"]);
  });

  it("空块列表 → 空组", () => {
    expect(groupBlocks([])).toHaveLength(0);
  });
});

describe("ELLIPSIS_NOISE", () => {
  it("常见噪声形态", () => {
    expect(ELLIPSIS_NOISE.test("...")).toBe(true);
    expect(ELLIPSIS_NOISE.test("…")).toBe(true);
    expect(ELLIPSIS_NOISE.test("。  …")).toBe(true);
    expect(ELLIPSIS_NOISE.test("")).toBe(true);
  });
  it("正常文本不匹配", () => {
    expect(ELLIPSIS_NOISE.test("好的，这就去做")).toBe(false);
    expect(ELLIPSIS_NOISE.test("a...b")).toBe(false);
  });
});

describe("parseDelta", () => {
  it("带 type 的对象透传", () => {
    expect(parseDelta({ type: "agent_end" })).toEqual({ type: "agent_end" });
  });
  it("无 type/非对象/null → null", () => {
    expect(parseDelta({})).toBeNull();
    expect(parseDelta("x")).toBeNull();
    expect(parseDelta(null)).toBeNull();
  });
});
