// App：启动 fetchMe → 按 auth 状态分流。loading 骨架 / anon → 登录页 / auth → ChatPage。
// 路由极简（无 URL 级 session 路由——会话状态在 store，刷新由 SSE messages_loaded 恢复）。

import { useEffect } from "react";
import { Skeleton } from "@/components/ui/skeleton";
import { TooltipProvider } from "@/components/ui/tooltip";
import { useAuth } from "@/store/auth";
import { useTheme } from "@/store/theme";
import { LoginPage } from "@/pages/LoginPage";
import { ChatPage } from "@/pages/ChatPage";

export function App() {
  const status = useAuth((s) => s.status);
  const init = useAuth((s) => s.init);
  const initTheme = useTheme((s) => s.init);

  useEffect(() => {
    initTheme();
    void init();
  }, [init, initTheme]);

  return (
    <TooltipProvider delayDuration={300}>
      {status === "loading" && (
        <main className="flex min-h-svh items-center justify-center bg-background">
          <div className="w-full max-w-sm space-y-4">
            <Skeleton className="mx-auto size-9 rounded-lg" />
            <Skeleton className="h-64 w-full rounded-xl" />
          </div>
        </main>
      )}
      {status === "anon" && <LoginPage />}
      {status === "auth" && <ChatPage />}
    </TooltipProvider>
  );
}
