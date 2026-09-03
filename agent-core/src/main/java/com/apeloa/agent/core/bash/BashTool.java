package com.apeloa.agent.core.bash;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.ToolSuspendException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * bash 工具（M1-8）：危险命令识别 + Permission/HITL 拦截 + 经 {@link CommandExecutor} 执行。
 *
 * <p>执行链：detector 识别 → {@link BashPermissionGate} 裁决 →
 * DENY 返回错误；ASK 抛 {@link ToolSuspendException} 挂起（框架转 RequireUserConfirmEvent，
 * HITL 确认后重放）；ALLOW 才真正执行。命令固定以 workspace 为工作目录。
 */
public class BashTool {

    static final int DEFAULT_TIMEOUT_SEC = 120;

    private final Path workspace;
    private final CommandExecutor executor;
    private final DangerousCommandDetector detector;
    private final BashPermissionGate gate;

    public BashTool(Path workspace, CommandExecutor executor,
            DangerousCommandDetector detector, BashPermissionGate gate) {
        this.workspace = workspace.toAbsolutePath().normalize();
        this.executor = executor;
        this.detector = detector;
        this.gate = gate;
    }

    /** 便捷构造：默认门禁（危险命中 → ASK）。 */
    public BashTool(Path workspace, CommandExecutor executor) {
        this(workspace, executor, new DangerousCommandDetector(), BashPermissionGate.dangerousAsks());
    }

    @Tool(name = "bash",
            description = "在 workspace 内执行 bash 命令，返回退出码与 stdout/stderr。"
                    + "长任务可用 timeout_sec 控制；输出超长会截断。",
            concurrencySafe = false)
    public String bash(
            @ToolParam(name = "command", description = "要执行的完整 bash 命令") String command,
            @ToolParam(name = "timeout_sec", required = false, description = "超时秒数（默认 120）") Integer timeoutSec) {
        int seconds = (timeoutSec == null || timeoutSec < 1) ? DEFAULT_TIMEOUT_SEC
                : Math.min(timeoutSec, 600);
        List<DangerousHit> hits = detector.detect(command);
        BashPermissionGate.Decision decision = gate.check(command, hits);
        switch (decision) {
            case DENY -> {
                return "错误：命令被权限策略拒绝：" + formatHits(hits);
            }
            case ASK -> throw new ToolSuspendException("等待人工确认危险命令：" + formatHits(hits));
            case ALLOW -> {
                // fall through 执行
            }
        }
        CommandExecutor.Outcome out = executor.execute(workspace, command, Duration.ofSeconds(seconds));
        if (out.timedOut()) {
            return String.format("命令超时（%ds），进程已终止%n--- stdout ---%n%s%n--- stderr ---%n%s",
                    seconds, out.stdout(), out.stderr());
        }
        return String.format("exit=%d%n--- stdout ---%n%s%n--- stderr ---%n%s",
                out.exitCode(), out.stdout(), out.stderr());
    }

    private String formatHits(List<DangerousHit> hits) {
        StringBuilder sb = new StringBuilder();
        for (DangerousHit h : hits) {
            sb.append(String.format("[%s] %s（命中：%s）", h.rule(), h.label(), h.matched()));
        }
        return sb.toString();
    }
}
