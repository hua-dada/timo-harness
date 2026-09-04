// 登录页：侘寂风——纸质渐变背景、朴素卡片、衬线品牌、暖阴影。
// 无 OIDC（M1-13 未交付）。设计语言取自侘寂规格：不对称（卡片略偏上）、
// 有机圆角、静谧交互（无花哨动效，仅提交态spinner）。

import { useState } from "react";
import { Terminal, LogIn, Moon, Sun } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { useAuth } from "@/store/auth";
import { useTheme } from "@/store/theme";

export function LoginPage() {
  const login = useAuth((s) => s.login);
  const toggle = useTheme((s) => s.toggle);
  const dark = useTheme((s) => s.theme === "dark");
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  async function onSubmit(e: React.FormEvent) {
    e.preventDefault();
    if (submitting) return;
    setError(null);
    setSubmitting(true);
    try {
      await login(username.trim(), password);
    } catch (err) {
      setError(err instanceof Error ? err.message : "登录失败");
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <main className="paper-texture flex min-h-svh flex-col bg-background p-6">
      {/* 主题切换（右上角，静谧小按钮） */}
      <div className="flex flex-1 justify-end">
        <Button variant="ghost" size="icon" className="size-8" onClick={toggle} title={dark ? "切到和纸（亮色）" : "切到炭墨（暗色）"}>
          {dark ? <Sun className="size-4" /> : <Moon className="size-4" />}
        </Button>
      </div>

      <div className="flex flex-1 items-center justify-center pb-24">
        <div className="w-full max-w-sm">
          {/* 品牌行：衬线 + 墨点印章式 logo（不对称：点在左上，字随其后） */}
          <div className="mb-10 flex items-baseline gap-2.5">
            <div className="flex size-8 items-center justify-center rounded-[10px] rounded-tr-[4px] bg-primary text-primary-foreground shadow-warm">
              <Terminal className="size-4" />
            </div>
            <span className="font-brand text-xl font-medium tracking-wide">timo-agent</span>
          </div>

          <form
            onSubmit={onSubmit}
            className="rounded-2xl rounded-tl-md border border-border bg-card p-7 shadow-warm-lg"
          >
            <h1 className="font-brand mb-1 text-lg font-medium">静心登录</h1>
            <p className="mb-6 text-sm text-muted-foreground">
              纸上得来终觉浅，绝知此事要躬行。
            </p>

            <label className="mb-1.5 block text-sm font-medium" htmlFor="username">
              用户名
            </label>
            <Input
              id="username"
              autoComplete="username"
              autoFocus
              required
              value={username}
              onChange={(e) => setUsername(e.target.value)}
              className="rounded-xl"
            />

            <label className="mt-4 mb-1.5 block text-sm font-medium" htmlFor="password">
              密码
            </label>
            <Input
              id="password"
              type="password"
              autoComplete="current-password"
              required
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              className="rounded-xl"
            />

            {error && (
              <div className="mt-4 rounded-lg border border-destructive/30 bg-destructive/10 px-3 py-2 text-sm text-destructive">
                {error}
              </div>
            )}

            <Button type="submit" className="mt-7 w-full rounded-xl" disabled={submitting}>
              {submitting ? (
                <span className="animate-spin size-4 rounded-full border-2 border-primary-foreground/30 border-t-primary-foreground" />
              ) : (
                <LogIn className="size-4" />
              )}
              {submitting ? "登录中…" : "登录"}
            </Button>
          </form>
        </div>
      </div>
    </main>
  );
}
