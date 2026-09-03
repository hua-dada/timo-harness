package com.apeloa.agent.core.bash;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * 进程输出捕获与超时治理：bash/docker 两类执行器共用的底层（吸取源项目孤儿进程教训）。
 *
 * <ul>
 *   <li>stdout/stderr 各一条守护线程读，避免管道缓冲区写满死锁；</li>
 *   <li>超时先杀子孙再杀本体（destroyForcibly 进程树），防孤儿残留；</li>
 *   <li>单路输出截断上限 64KB，防刷屏撑爆上下文。</li>
 * </ul>
 */
public final class ProcessRunner {

    static final int MAX_STREAM_CHARS = 64 * 1024;

    /** 命令执行结果（stdout/stderr 分开保留，UTF-8 解码）。 */
    public record Outcome(int exitCode, String stdout, String stderr, boolean timedOut) {

        public static Outcome of(int exitCode, String stdout, String stderr) {
            return new Outcome(exitCode, stdout, stderr, false);
        }
    }

    private ProcessRunner() {
    }

    /** 启动 argv 并在 timeout 内等待完成；超时强杀进程树。workdir 可为 null（继承当前目录）。 */
    public static Outcome run(ProcessBuilder pb, Duration timeout) {
        try {
            Process process = pb.start();
            StringBuilder stdout = new StringBuilder();
            StringBuilder stderr = new StringBuilder();
            Thread outReader = pump(process.getInputStream(), stdout);
            Thread errReader = pump(process.getErrorStream(), stderr);
            boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.descendants().forEach(ph -> ph.destroyForcibly());
                process.destroyForcibly();
                return new Outcome(-1, stdout.toString(), stderr.toString(), true);
            }
            outReader.join(2000);
            errReader.join(2000);
            return Outcome.of(process.exitValue(), stdout.toString(), stderr.toString());
        } catch (IOException e) {
            return Outcome.of(-1, "", "启动进程失败（命令不在 PATH 上？）：" + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Outcome.of(-1, "", "执行被中断：" + e.getMessage());
        }
    }

    private static Thread pump(InputStream in, StringBuilder sink) {
        Thread t = new Thread(() -> {
            byte[] buf = new byte[8192];
            try (InputStream is = in) {
                int n;
                while ((n = is.read(buf)) >= 0) {
                    if (sink.length() < MAX_STREAM_CHARS) {
                        sink.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                    }
                }
                if (sink.length() >= MAX_STREAM_CHARS) {
                    sink.append("\n…（输出超过 ").append(MAX_STREAM_CHARS / 1024).append("KB，已截断）");
                }
            } catch (IOException ignored) {
                // 进程被杀导致的流关闭属正常路径
            }
        });
        t.setDaemon(true);
        t.start();
        return t;
    }
}
