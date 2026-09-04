// 聊天页：三栏（侧栏 | 消息流+composer | 可折叠文件面板）。
// 顶栏：会话名 + 连接状态 + 面板开关。移动端 <1024px 隐藏侧栏（M1 不做 Sheet 抽屉深适配）。
// 卸载时断开 SSE；登出经侧栏按钮（store 里 disconnect 由本组件卸载触发）。

import { useEffect } from "react";
import { PanelRight, PanelRightClose, Moon, Sun } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Tip } from "@/components/ui/tooltip";
import { ConnectionStatus } from "@/components/common/ConnectionStatus";
import { SessionsSidebar } from "@/components/chat/SessionsSidebar";
import { MessageList } from "@/components/chat/MessageList";
import { MessageInput } from "@/components/chat/MessageInput";
import { WelcomeHero } from "@/components/chat/WelcomeHero";
import { ResourcePanel } from "@/components/files/ResourcePanel";
import { useChat } from "@/store/chat";
import { useChatPanelOpen, setPanelOpen } from "@/store/panel";
import { useTheme } from "@/store/theme";
import { cn } from "@/lib/utils";

export function ChatPage() {
  const sessionName = useChat((s) => s.delta.sessionName);
  const sseState = useChat((s) => s.sseState);
  const thinkingLevel = useChat((s) => s.delta.thinkingLevel);
  const activeSessionId = useChat((s) => s.activeSessionId);

  useEffect(() => {
    return () => useChat.getState().disconnect();
  }, []);

  return (
    <div className="paper-texture flex h-svh overflow-hidden bg-background text-foreground">
      <aside className="hidden shrink-0 lg:block">
        <SessionsSidebar />
      </aside>

      <main className="flex min-w-0 flex-1 flex-col">
        <header className="flex h-12 shrink-0 items-center gap-3 border-b border-border px-4">
          <div className="min-w-0 flex-1">
            <span className="font-brand block truncate text-sm font-medium">
              {activeSessionId
                ? (sessionName ?? "加载中…")
                : "新对话"}
            </span>
          </div>
          <span className="hidden text-xs text-muted-foreground/60 sm:inline">
            思考档位 {thinkingLevel}
          </span>
          <ConnectionStatus state={activeSessionId ? sseState : "closed"} />
          <ThemeToggle />
          <PanelToggle />
        </header>

        {/* 无会话：欢迎页（发送即新建会话）；有会话：消息流 + composer */}
        {activeSessionId ? (
          <>
            <MessageList />
            <MessageInput />
          </>
        ) : (
          <WelcomeHero />
        )}
      </main>

      <ResourcePanel className="hidden xl:flex" />
    </div>
  );
}

/** 侘寂双主题切换（和纸亮 / 炭墨暗）。 */
function ThemeToggle() {
  const toggle = useTheme((s) => s.toggle);
  const dark = useTheme((s) => s.theme === "dark");
  return (
    <Tip label={dark ? "切到和纸（亮色）" : "切到炭墨（暗色）"}>
      <Button variant="ghost" size="icon" className="size-8 shrink-0" onClick={toggle}>
        {dark ? <Sun className="size-4" /> : <Moon className="size-4" />}
      </Button>
    </Tip>
  );
}

/** 文件面板开关（内部自管展开态；面板自身 xl 以下整体隐藏）。 */
function PanelToggle() {
  const open = useChatPanelOpen();
  return (
    <Tip label={open ? "收起文件面板" : "展开文件面板"}>
      <Button
        variant="ghost"
        size="icon"
        className={cn("hidden size-8 shrink-0 xl:flex", open && "text-muted-foreground")}
        onClick={() => setPanelOpen(!open)}
      >
        {open ? <PanelRightClose className="size-4" /> : <PanelRight className="size-4" />}
      </Button>
    </Tip>
  );
}

