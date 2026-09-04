// HITL 确认浮层 store。源的应答走 WS extension_ui_response；本仓走 HTTP
// POST /api/hitl/{id}，且后端 abort 时自动按全部拒绝处理——前端只需在收到
// error/agent_end 或会话切换时 clear，不重复应答。

import { create } from "zustand";
import { respondHitl, type HitlAction } from "@/lib/api";

export interface PendingRequest {
  readonly id: string;
  readonly method: string;
  readonly title?: string;
}

interface UiRequestsState {
  readonly pending: PendingRequest | null;
  /** 应答中（防重复点击）。 */
  readonly responding: boolean;
  readonly error: string | null;
  /** reducer 副作用 uiRequests 到达时调用（幂等：同 id 不覆盖）。 */
  push: (request: PendingRequest) => void;
  /** 应答：POST /api/hitl；成功清浮层，失败置 error 保留请求。 */
  respond: (action: HitlAction, args?: Record<string, unknown>) => Promise<void>;
  /** 丢弃浮层不向后端应答（error/agent_end/切会话时）。 */
  clear: () => void;
}

export const useUiRequests = create<UiRequestsState>((set, get) => ({
  pending: null,
  responding: false,
  error: null,

  push: (request) => {
    const { pending } = get();
    if (pending && pending.id === request.id) return;
    set({ pending: request, error: null, responding: false });
  },

  respond: async (action, args) => {
    const { pending, responding } = get();
    if (!pending || responding) return;
    set({ responding: true, error: null });
    try {
      await respondHitl(pending.id, action, args);
      set({ pending: null, responding: false, error: null });
    } catch (e) {
      set({ responding: false, error: e instanceof Error ? e.message : "应答失败" });
    }
  },

  clear: () => set({ pending: null, responding: false, error: null }),
}));
