// 登录态 store：loading（启动 fetchMe 中）→ anon / auth。
// 与源 auth store 语义一致：App 启动先 fetchMe，401 落 anon。

import { create } from "zustand";
import { fetchMe, login as apiLogin, logout as apiLogout, type Me } from "@/lib/api";

export type AuthStatus = "loading" | "anon" | "auth";

interface AuthState {
  readonly status: AuthStatus;
  readonly me: Me | null;
  /** 启动探测：fetchMe，401 → anon（静默）；网络异常 → anon（登录页再试）。 */
  init: () => Promise<void>;
  /** 登录成功写入 me；失败 throw（LoginPage 展示错误）。 */
  login: (username: string, password: string) => Promise<void>;
  /** 登出：无论成败都复位 anon（chat store 的关闭由 ChatPage 卸载/App 层处理）。 */
  logout: () => Promise<void>;
}

export const useAuth = create<AuthState>((set) => ({
  status: "loading",
  me: null,

  init: async () => {
    try {
      const me = await fetchMe();
      set({ status: "auth", me });
    } catch {
      set({ status: "anon", me: null });
    }
  },

  login: async (username, password) => {
    const me = await apiLogin(username, password);
    set({ status: "auth", me });
  },

  logout: async () => {
    try {
      await apiLogout();
    } finally {
      set({ status: "anon", me: null });
    }
  },
}));
