package com.agent.timo.poc;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.AgentStartEvent;
import io.agentscope.core.event.ExceedMaxItersEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.message.UserMessage;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/**
 * M1-4 PoC：验证 AgentScope 进程内 Agent 链路
 * （Spring 自动装配 → OpenAI 兼容模型 → new-api 网关 → 类型化事件流）。
 *
 * <p>仅在 {@code ReActAgent} bean 存在时执行，即 {@code --spring.profiles.active=poc}
 * （见 application-poc.yml：设 {@code agentscope.model.provider=openai} 才会创建模型与 Agent
 * bean）。默认 profile 与测试 profile 下 bean 缺失，此处直接跳过 —— 启动干净、不触发网络。
 *
 * <p>事件分发结构（{@link #logEvent}）是 M2 SSE 网关的雏形：AgentScope 的类型化事件
 * 将在那里映射为源项目的 PiEvent 协议（message_update / tool_execution_start / …）。
 */
@Component
public class AgentScopePoCRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(AgentScopePoCRunner.class);

    /** 兜底超时：new-api 无响应时不挂死启动流程。 */
    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    private final ObjectProvider<ReActAgent> agentProvider;
    private final String prompt;

    public AgentScopePoCRunner(
            ObjectProvider<ReActAgent> agentProvider,
            @Value("${app.poc.agentscope.prompt:}") String prompt) {
        this.agentProvider = agentProvider;
        this.prompt = prompt;
    }

    @Override
    public void run(String... args) {
        ReActAgent agent = agentProvider.getIfAvailable();
        if (agent == null) {
            log.debug("AgentScope PoC 跳过：无 ReActAgent bean（未激活 poc profile）");
            return;
        }
        if (prompt.isBlank()) {
            log.warn("AgentScope PoC 跳过：app.poc.agentscope.prompt 为空");
            return;
        }

        log.info("AgentScope PoC 开始，prompt={}", prompt);
        StringBuilder answer = new StringBuilder();
        try {
            agent.streamEvents(new UserMessage(prompt))
                    .doOnNext(event -> logEvent(event, answer))
                    .blockLast(TIMEOUT);
            log.info("AgentScope PoC 完成，最终回复：{}", answer.toString().strip());
        } catch (RuntimeException e) {
            // PoC 失败不阻断启动：new-api 不可达 / key 无效时仅告警，其余上下文照常可用。
            log.error("AgentScope PoC 失败（检查 NEWAPI_BASE_URL / NEWAPI_API_KEY 可达性）：{}",
                    e.toString());
        }
    }

    /** 按事件子类型打印，验证类型化事件流可被逐一消费；未覆盖的类型走 default 只记类型名。 */
    private void logEvent(AgentEvent event, StringBuilder answer) {
        switch (event) {
            case AgentStartEvent e ->
                    log.info("[{}] name={} replyId={}", e.getType(), e.getName(), e.getReplyId());
            case ThinkingBlockDeltaEvent e -> log.debug("[{}] {}", e.getType(), e.getDelta());
            case TextBlockDeltaEvent e -> {
                answer.append(e.getDelta());
                log.debug("[{}] {}", e.getType(), e.getDelta());
            }
            case ToolCallStartEvent e ->
                    log.info("[{}] tool={} id={}", e.getType(), e.getToolCallName(), e.getToolCallId());
            case ExceedMaxItersEvent e ->
                    log.warn("[{}] 迭代 {}/{} 触顶", e.getType(), e.getCurrentIter(), e.getMaxIters());
            case AgentResultEvent e ->
                    log.info("[{}] {}", e.getType(), e.getResult().getTextContent());
            case AgentEndEvent e -> log.info("[{}] replyId={}", e.getType(), e.getReplyId());
            default -> log.debug("[{}]", event.getType());
        }
    }
}
