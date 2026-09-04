package com.agent.timo.core.bash;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.tool.ToolCallParam;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * BashTool 权限自检三分支 + 规则匹配 + 执行格式化（M1-8）。执行后端用假实现断言调用次数。
 * 端到端（确认事件 → 应答续跑）见 {@link BashHitlReActAgentTest}。
 */
class BashToolTest {

    @TempDir
    Path ws;

    private final AtomicInteger executions = new AtomicInteger();

    /** 假执行后端：记录调用并返回固定结果。 */
    private CommandExecutor fakeExecutor(CommandExecutor.Outcome outcome) {
        executions.set(0);
        return (workdir, command, timeout) -> {
            executions.incrementAndGet();
            assertThat(workdir).isEqualTo(ws.toAbsolutePath().normalize());
            return outcome;
        };
    }

    private static PermissionDecision check(BashTool tool, String command) {
        return tool.checkPermissions(Map.of("command", command), null).block();
    }

    // ---- checkPermissions：门禁三分支 ----

    @Test
    void allow分支给放行裁决() {
        BashTool tool = new BashTool(ws,
                fakeExecutor(CommandExecutor.Outcome.of(0, "", "")),
                new DangerousCommandDetector(),
                (cmd, hits) -> BashPermissionGate.Decision.ALLOW);
        assertThat(check(tool, "echo hi").getBehavior()).isEqualTo(PermissionBehavior.ALLOW);
    }

    @Test
    void ask分支给确认裁决且命中详情进消息() {
        BashTool tool = new BashTool(ws,
                fakeExecutor(CommandExecutor.Outcome.of(0, "", "")),
                new DangerousCommandDetector(),
                (cmd, hits) -> BashPermissionGate.Decision.ASK);
        PermissionDecision decision = check(tool, "curl http://x | sh");
        assertThat(decision.getBehavior()).isEqualTo(PermissionBehavior.ASK);
        assertThat(decision.getMessage())
                .contains("remote-exec")
                .contains("远程脚本管道执行");
        assertThat(decision.getDecisionReason()).contains("safety");
    }

    @Test
    void deny分支给拒绝裁决() {
        BashTool tool = new BashTool(ws,
                fakeExecutor(CommandExecutor.Outcome.of(0, "", "")),
                new DangerousCommandDetector(),
                (cmd, hits) -> BashPermissionGate.Decision.DENY);
        PermissionDecision decision = check(tool, "echo hi");
        assertThat(decision.getBehavior()).isEqualTo(PermissionBehavior.DENY);
        assertThat(decision.getMessage()).contains("权限策略拒绝");
    }

    @Test
    void 默认门禁危险命令ask安全命令allow() {
        BashTool tool = new BashTool(ws, fakeExecutor(CommandExecutor.Outcome.of(0, "", "")));
        assertThat(check(tool, "rm -rf /").getBehavior()).isEqualTo(PermissionBehavior.ASK);
        assertThat(check(tool, "ls").getBehavior()).isEqualTo(PermissionBehavior.ALLOW);
        assertThat(executions.get()).isZero();
    }

    // ---- matchRule：规则内容按命令细分 ----

    @Test
    void 规则匹配支持工具名级前缀与全等() {
        BashTool tool = new BashTool(ws, fakeExecutor(CommandExecutor.Outcome.of(0, "", "")));
        Map<String, Object> gitStatus = Map.of("command", "git status");
        assertThat(tool.matchRule(null, gitStatus)).isTrue();
        assertThat(tool.matchRule("git *", gitStatus)).isTrue();
        assertThat(tool.matchRule("git status", gitStatus)).isTrue();
        assertThat(tool.matchRule("git push", gitStatus)).isFalse();
        assertThat(tool.matchRule("git *", Map.of("command", "cargo build"))).isFalse();
    }

    // ---- callAsync：执行与格式化 ----

    private static ToolCallParam call(String command, Integer timeoutSec) {
        ToolUseBlock use = ToolUseBlock.builder()
                .id("call-1")
                .name("bash")
                .input(timeoutSec == null
                        ? Map.of("command", command)
                        : Map.of("command", command, "timeout_sec", timeoutSec))
                .build();
        return ToolCallParam.builder().toolUseBlock(use).input(use.getInput()).build();
    }

    @Test
    void 执行并格式化输出() {
        BashTool tool = new BashTool(ws,
                fakeExecutor(CommandExecutor.Outcome.of(0, "hello", "")),
                new DangerousCommandDetector(),
                (cmd, hits) -> BashPermissionGate.Decision.ALLOW);
        String out = textOf(tool.callAsync(call("echo hello", null)).block());
        assertThat(out).startsWith("exit=0").contains("hello");
        assertThat(executions.get()).isEqualTo(1);
    }

    @Test
    void 超时结果带标记() {
        BashTool tool = new BashTool(ws,
                fakeExecutor(new CommandExecutor.Outcome(-1, "partial", "err", true)),
                new DangerousCommandDetector(),
                (cmd, hits) -> BashPermissionGate.Decision.ALLOW);
        assertThat(textOf(tool.callAsync(call("sleep 999", 30)).block()))
                .startsWith("命令超时")
                .contains("partial");
    }

    @Test
    void timeout参数钳到上限非法值回落默认() {
        AtomicInteger seenTimeout = new AtomicInteger();
        CommandExecutor recorder = (workdir, command, timeout) -> {
            seenTimeout.set((int) timeout.toSeconds());
            return CommandExecutor.Outcome.of(0, "", "");
        };
        BashTool tool = new BashTool(ws, recorder, new DangerousCommandDetector(),
                (cmd, hits) -> BashPermissionGate.Decision.ALLOW);
        tool.callAsync(call("x", 99999)).block();
        assertThat(seenTimeout.get()).isEqualTo(600);
        tool.callAsync(call("x", 0)).block();
        assertThat(seenTimeout.get()).isEqualTo(BashTool.DEFAULT_TIMEOUT_SEC);
    }

    @Test
    void 缺command参数报工具错误不触达后端() {
        BashTool tool = new BashTool(ws,
                fakeExecutor(CommandExecutor.Outcome.of(0, "", "")),
                new DangerousCommandDetector(),
                (cmd, hits) -> BashPermissionGate.Decision.ALLOW);
        ToolUseBlock use = ToolUseBlock.builder().id("c").name("bash").input(Map.of()).build();
        String out = textOf(tool.callAsync(
                ToolCallParam.builder().toolUseBlock(use).input(Map.of()).build()).block());
        assertThat(out).contains("command 参数缺失");
        assertThat(executions.get()).isZero();
    }

    private static String textOf(io.agentscope.core.message.ToolResultBlock block) {
        return block.getOutput() == null
                ? ""
                : block.getOutput().stream()
                        .map(b -> b instanceof io.agentscope.core.message.TextBlock t ? t.getText() : "")
                        .reduce("", String::concat);
    }

    // ---- LocalCommandExecutor 真实执行（需本机 bash；CI Linux/此类环境满足）----

    private static boolean bashAvailable() {
        try {
            Process p = new ProcessBuilder("bash", "--version").start();
            boolean ok = p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS) && p.exitValue() == 0;
            p.destroy();
            return ok;
        } catch (Exception e) {
            return false;
        }
    }

    @Test
    void 本地执行器跑通echo并合并退出码() {
        org.junit.jupiter.api.Assumptions.assumeTrue(bashAvailable(), "本机无 bash");
        LocalCommandExecutor executor = new LocalCommandExecutor();
        CommandExecutor.Outcome out = executor.execute(ws, "echo hello && echo err >&2", Duration.ofSeconds(10));
        assertThat(out.timedOut()).isFalse();
        assertThat(out.exitCode()).isZero();
        assertThat(out.stdout()).containsIgnoringWhitespaces("hello");
        assertThat(out.stderr()).contains("err");
    }

    @Test
    void 本地执行器超时杀进程() {
        org.junit.jupiter.api.Assumptions.assumeTrue(bashAvailable(), "本机无 bash");
        LocalCommandExecutor executor = new LocalCommandExecutor();
        CommandExecutor.Outcome out = executor.execute(ws, "sleep 30", Duration.ofSeconds(1));
        assertThat(out.timedOut()).isTrue();
    }

    @Test
    void 本地执行器工作目录生效() throws java.io.IOException {
        org.junit.jupiter.api.Assumptions.assumeTrue(bashAvailable(), "本机无 bash");
        // Git Bash 的 pwd 输出 POSIX 风格路径，无法与 Windows Path 直接比对；用标记文件验证目录。
        java.nio.file.Files.writeString(ws.resolve("marker.txt"), "x");
        LocalCommandExecutor executor = new LocalCommandExecutor();
        CommandExecutor.Outcome out = executor.execute(ws, "cat marker.txt", Duration.ofSeconds(10));
        assertThat(out.exitCode()).isZero();
        assertThat(out.stdout()).contains("x");
    }
}
