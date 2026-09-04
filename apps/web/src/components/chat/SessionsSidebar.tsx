// 会话侧栏：侘寂纸面（暖渐变+噪点）、衬线品牌、朴素列表、静寂悬浮。
// 无删除（后端无 DELETE 路由）、无重命名——M1 出界。

import { useEffect } from "react";
import { Plus, MessageSquare, LogOut, Loader2 } from "lucide-react";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";
import { useChat } from "@/store/chat";
import { useAuth } from "@/store/auth";

export function SessionsSidebar({ className }: { className?: string }) {
  const sessions = useChat((s) => s.sessions);
  const activeSessionId = useChat((s) => s.activeSessionId);
  const listLoading = useChat((s) => s.listLoading);
  const loadSessions = useChat((s) => s.loadSessions);
  const newSession = useChat((s) => s.newSession);
  const switchSession = useChat((s) => s.switchSession);
  const logout = useAuth((s) => s.logout);

  useEffect(() => {
    void loadSessions();
  }, [loadSessions]);

  return (
    <div className={cn("paper-texture flex h-full w-64 flex-col border-r border-border bg-card", className)}>
      <div className="flex items-center gap-2.5 px-4 pt-4 pb-3">
        <div className="flex size-7 items-center justify-center rounded-[9px] rounded-tr-[3px] bg-primary text-primary-foreground shadow-warm">
          <MessageSquare className="size-3.5" />
        </div>
        <span className="font-brand text-sm font-medium tracking-wide">timo-agent</span>
      </div>

      <div className="px-3 pb-3">
        <Button
          variant="secondary"
          size="sm"
          className="w-full rounded-xl"
          onClick={() => void newSession().catch(() => undefined)}
        >
          <Plus className="size-4" />
          新对话
        </Button>
      </div>

      <nav className="scroll-subtle flex-1 overflow-y-auto px-2 pb-2">
        {listLoading && sessions.length === 0 && (
          <div className="flex items-center justify-center py-8 text-muted-foreground">
            <Loader2 className="size-4 animate-spin" />
          </div>
        )}
        {!listLoading && sessions.length === 0 && (
          <p className="px-2 py-8 text-center text-xs text-muted-foreground">
            还没有会话，点「新对话」开始
          </p>
        )}
        <ul className="space-y-1">
          {sessions.map((s) => {
            const active = s.sessionId === activeSessionId;
            return (
              <li key={s.sessionId}>
                <button
                  className={cn(
                    "w-full truncate rounded-[10px] rounded-tl-sm px-3 py-2 text-left text-sm transition-colors",
                    active
                      ? "bg-accent text-accent-foreground shadow-warm"
                      : "text-foreground/75 hover:bg-accent/50",
                  )}
                  onClick={() => switchSession(s.sessionId)}
                  title={s.name}
                >
                  <span className="block truncate">{s.name}</span>
                </button>
              </li>
            );
          })}
        </ul>
      </nav>

      <div className="border-t border-border p-3">
        <Button
          variant="ghost"
          size="sm"
          className="w-full justify-start rounded-[10px]"
          onClick={() => void logout()}
        >
          <LogOut className="size-4" />
          登出
        </Button>
      </div>
    </div>
  );
}
