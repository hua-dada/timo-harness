// 面板开关 store：从 ChatPage 抽出，避免 ChatPage ↔ ResourcePanel 循环导入。

import { create } from "zustand";

const panelStore = create<{ open: boolean }>(() => ({ open: true }));

export function useChatPanelOpen(): boolean {
  return panelStore((s) => s.open);
}

export function setPanelOpen(open: boolean): void {
  panelStore.setState({ open });
}
