package com.apeloa.agent.core.bash;

import java.nio.file.Path;
import java.time.Duration;

/**
 * 本地 {@link CommandExecutor}：bash -c 执行（M1 开发模式）。
 *
 * <p>输出按 UTF-8 解码（沙箱/容器内 locale 统一 UTF-8；Windows 控制台的 GBK 输出属宿主噪音，
 * 不迁就）。超时/输出捕获由 {@link ProcessRunner} 统一治理（杀进程树防孤儿 + 64KB 截断）。
 */
public class LocalCommandExecutor implements CommandExecutor {

    @Override
    public Outcome execute(Path workdir, String command, Duration timeout) {
        ProcessBuilder pb = new ProcessBuilder("bash", "-c", command)
                .directory(workdir.toFile());
        pb.environment().put("LANG", "C.UTF-8");
        ProcessRunner.Outcome out = ProcessRunner.run(pb, timeout);
        if (out.timedOut()) {
            return new Outcome(out.exitCode(), out.stdout(), out.stderr(), true);
        }
        return Outcome.of(out.exitCode(), out.stdout(), out.stderr());
    }
}
