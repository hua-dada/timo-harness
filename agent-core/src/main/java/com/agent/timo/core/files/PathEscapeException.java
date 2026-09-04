package com.agent.timo.core.files;

/**
 * 路径越权异常：用户输入的相对路径试图逃出 workspace（../ 穿越 / 伪前缀 / 空字节 / 绝对路径外逃）。
 *
 * <p>移植自源项目 apps/server/src/files/safe-path.ts 的 PathEscapeError。
 */
public class PathEscapeException extends RuntimeException {

    public PathEscapeException(String rel) {
        super("路径越权：" + rel);
    }
}
