package com.agent.timo.core.files;

import java.nio.file.Path;

/**
 * 沙箱路径安全：把用户（模型）输入的相对路径解析到 workspace 内，防 ../ 穿越与绝对路径外逃。
 *
 * <p>移植自源项目 apps/server/src/files/safe-path.ts 的 resolveUserPath，语义对齐：
 * <ul>
 *   <li>去前导 {@code /}（"/etc/x" 视作 workspace 内 "etc/x"，绝不外逃）；</li>
 *   <li>normalize 后校验等于 workspace 或以 workspace 为前缀路径（防伪前缀）；</li>
 *   <li>空字节一律拒绝（可绕过日志、欺骗路径显示）。</li>
 * </ul>
 *
 * <p>与 TS 版的差异：Windows 上 Java 的 {@link Path} 把 {@code \} 当分隔符，
 * {@code ..\..\evil} 会被 normalize 解析为上越后同样被前缀校验拦截（TS 版仅在 win32 跑该用例）。
 */
public final class SafePath {

    private SafePath() {
    }

    /**
     * 把 rel 解析到 workspace 内的绝对路径；越权抛 {@link PathEscapeException}。
     *
     * @param workspace workspace 根（绝对路径；相对则按当前目录补全）
     * @param rel       用户输入的相对路径；空串 / "." 返回 workspace 本身
     */
    public static Path resolveUserPath(Path workspace, String rel) {
        Path ws = workspace.toAbsolutePath().normalize();
        if (rel.indexOf('\0') >= 0) {
            throw new PathEscapeException(rel);
        }
        String cleaned = rel.replaceFirst("^/+", "").trim();
        Path normalized = cleaned.isEmpty() ? ws : ws.resolve(cleaned).normalize();
        if (!normalized.equals(ws) && !normalized.startsWith(ws)) {
            throw new PathEscapeException(rel);
        }
        return normalized;
    }
}
