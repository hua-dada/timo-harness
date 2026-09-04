// 主题切换（侘寂双主题：和纸亮 / 炭墨暗）。默认暗色（编码工具惯例），
// 选择存 localStorage，<html class="dark"> 切换。

import { create } from "zustand";

type Theme = "light" | "dark";

const KEY = "wabi-sabi-theme";

function initial(): Theme {
  try {
    const saved = localStorage.getItem(KEY);
    if (saved === "light" || saved === "dark") return saved;
  } catch {
    // localStorage 不可用（隐私模式等）：回落暗色
  }
  return "dark";
}

function apply(theme: Theme) {
  document.documentElement.classList.toggle("dark", theme === "dark");
}

interface ThemeState {
  readonly theme: Theme;
  toggle: () => void;
  /** 启动时调一次：读偏好并落到 <html>。 */
  init: () => void;
}

export const useTheme = create<ThemeState>((set, get) => ({
  theme: "dark",
  init: () => {
    const theme = initial();
    apply(theme);
    set({ theme });
  },
  toggle: () => {
    const theme: Theme = get().theme === "dark" ? "light" : "dark";
    apply(theme);
    try {
      localStorage.setItem(KEY, theme);
    } catch {
      // 存不进就算了：本次会话内仍生效
    }
    set({ theme });
  },
}));
