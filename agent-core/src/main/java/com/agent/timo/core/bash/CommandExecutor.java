package com.agent.timo.core.bash;

import java.nio.file.Path;
import java.time.Duration;

/**
 * 命令执行后端 SPI。M1 本地模式用 {@link LocalCommandExecutor}（bash -c）；
 * M1-9 沙箱落地后切换 Docker 实现（docker exec --user \<uid\> 共享容器），
 * 对应技术设计 2.2-B「Workspace backend 可切换」。
 */
public interface CommandExecutor {

    /**
     * 在 workdir 下执行 shell 命令。
     *
     * @param workdir  工作目录（workspace 根）
     * @param command  完整 shell 命令
     * @param timeout  超时；超时杀进程并置 {@link Outcome#timedOut()}
     */
    Outcome execute(Path workdir, String command, Duration timeout);

    /** 命令执行结果（stdout/stderr 分开保留，UTF-8 解码）。 */
    record Outcome(int exitCode, String stdout, String stderr, boolean timedOut) {

        public static Outcome of(int exitCode, String stdout, String stderr) {
            return new Outcome(exitCode, stdout, stderr, false);
        }
    }
}
