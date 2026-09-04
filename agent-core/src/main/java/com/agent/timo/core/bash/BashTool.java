package com.agent.timo.core.bash;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import reactor.core.publisher.Mono;

/**
 * bash 工具（M1-8）：危险命令识别 + 框架 Permission/HITL 拦截 + 经 {@link CommandExecutor} 执行。
 *
 * <p><b>拦截机制</b>：本类是 {@link ToolBase} 子类，{@link #checkPermissions} 把
 * {@link BashPermissionGate} 的裁决翻译成 {@link PermissionDecision}——ASK 时框架发
 * {@code RequireUserConfirmEvent} 暂停（M1-6 SSE 桥映射为前端 ui_request 浮层，应答经
 * {@code POST /api/hitl/{id}} → {@code ConfirmResult} 续跑，块被标 ALLOWED 不再询问）、
 * DENY 时框架合成拒绝结果不执行。比旧方案（直接抛 {@code ToolSuspendException}）多出
 * 完整应答回路：挂起异常只会变成 suspended 结果块，框架不发确认事件，前端没有通道可答。
 *
 * <p><b>裁决优先级</b>：引擎先查 ask/deny 规则再调本工具自检（PermissionEngine 评估顺序
 * 1/2 → 3），故用户规则可以越过工具自检的 ALLOW 收紧；工具自检 ALLOW 后引擎不再回落
 * 默认 ASK，安全命令不因权限系统启用而无端打扰。命令固定以 workspace 为工作目录执行。
 */
public class BashTool extends ToolBase {

    static final int DEFAULT_TIMEOUT_SEC = 120;
    private static final int MAX_TIMEOUT_SEC = 600;

    /** OpenAI 风格入参 schema（对齐原 @Tool 方法签名：command 必填、timeout_sec 可选）。 */
    private static final Map<String, Object> INPUT_SCHEMA =
            Map.of(
                    "type", "object",
                    "properties",
                            Map.of(
                                    "command",
                                    Map.of(
                                            "type", "string",
                                            "description", "要执行的完整 bash 命令"),
                                    "timeout_sec",
                                    Map.of(
                                            "type", "integer",
                                            "description", "超时秒数（默认 120）")),
                    "required", List.of("command"));

    private final Path workspace;
    private final CommandExecutor executor;
    private final DangerousCommandDetector detector;
    private final BashPermissionGate gate;

    public BashTool(Path workspace, CommandExecutor executor,
            DangerousCommandDetector detector, BashPermissionGate gate) {
        // 框架的 Builder 无 build()：builder 实例直接喂给 protected ToolBase(Builder)。
        super(ToolBase.builder()
                .name("bash")
                .description("在 workspace 内执行 bash 命令，返回退出码与 stdout/stderr。"
                        + "长任务可用 timeout_sec 控制；输出超长会截断。")
                .inputSchema(INPUT_SCHEMA)
                .readOnly(false)
                .concurrencySafe(false));
        this.workspace = workspace.toAbsolutePath().normalize();
        this.executor = executor;
        this.detector = detector;
        this.gate = gate;
    }

    /** 便捷构造：默认门禁（危险命中 → ASK）。 */
    public BashTool(Path workspace, CommandExecutor executor) {
        this(workspace, executor, new DangerousCommandDetector(), BashPermissionGate.dangerousAsks());
    }

    /**
     * 工具自检（PermissionEngine 评估顺序第 3 步）：detector 识别 → gate 裁决。
     * ASK 的 {@code decisionReason} 带 {@code safety} 关键字，引擎据此挂 suggested rules。
     */
    @Override
    public Mono<PermissionDecision> checkPermissions(
            Map<String, Object> toolInput, PermissionContextState context) {
        String command = commandOf(toolInput);
        List<DangerousHit> hits = detector.detect(command);
        return switch (gate.check(command, hits)) {
            case DENY -> Mono.just(
                    PermissionDecision.deny("命令被权限策略拒绝：" + formatHits(hits)));
            case ASK -> Mono.just(PermissionDecision.builder()
                    .behavior(PermissionBehavior.ASK)
                    .message("等待人工确认危险命令：" + formatHits(hits))
                    .decisionReason("safety: dangerous command hit")
                    .build());
            case ALLOW -> Mono.just(PermissionDecision.allow("无危险命中，放行"));
        };
    }

    /**
     * 规则内容匹配：{@code null}/空 = 工具名级规则恒匹配；{@code xxx*} = 命令前缀；
     * 其余 = 命令全等。供 ask/allow/deny 规则按命令细分（如 allow {@code git status*}）。
     */
    @Override
    public boolean matchRule(String ruleContent, Map<String, Object> toolInput) {
        if (ruleContent == null || ruleContent.isEmpty()) {
            return true;
        }
        String command = commandOf(toolInput);
        if (command == null) {
            return false;
        }
        if (ruleContent.endsWith("*")) {
            return command.startsWith(ruleContent.substring(0, ruleContent.length() - 1));
        }
        return command.equals(ruleContent);
    }

    /** 执行（只有框架裁决通过后才会到达这里）。退出码非 0 / 超时对模型是正常文本结果，不是工具错误。 */
    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        Map<String, Object> input = param.getInput() == null ? Map.of() : param.getInput();
        String command = commandOf(input);
        if (command == null || command.isBlank()) {
            return Mono.just(ToolResultBlock.error("command 参数缺失"));
        }
        int seconds = timeoutOf(input);
        CommandExecutor.Outcome out = executor.execute(workspace, command, Duration.ofSeconds(seconds));
        String text = out.timedOut()
                ? String.format("命令超时（%ds），进程已终止%n--- stdout ---%n%s%n--- stderr ---%n%s",
                        seconds, out.stdout(), out.stderr())
                : String.format("exit=%d%n--- stdout ---%n%s%n--- stderr ---%n%s",
                        out.exitCode(), out.stdout(), out.stderr());
        return Mono.just(ToolResultBlock.text(text));
    }

    private static String commandOf(Map<String, Object> input) {
        Object command = input == null ? null : input.get("command");
        return command instanceof String s ? s : null;
    }

    /** JSON 解析出的整数可能是 Integer/Long/Double，统一按 Number 收口并钳到 [1, 600]。 */
    private static int timeoutOf(Map<String, Object> input) {
        Object raw = input == null ? null : input.get("timeout_sec");
        int seconds = raw instanceof Number n ? n.intValue() : DEFAULT_TIMEOUT_SEC;
        return (seconds < 1) ? DEFAULT_TIMEOUT_SEC : Math.min(seconds, MAX_TIMEOUT_SEC);
    }

    private String formatHits(List<DangerousHit> hits) {
        StringBuilder sb = new StringBuilder();
        for (DangerousHit h : hits) {
            sb.append(String.format("[%s] %s（命中：%s）", h.rule(), h.label(), h.matched()));
        }
        return sb.toString();
    }
}
