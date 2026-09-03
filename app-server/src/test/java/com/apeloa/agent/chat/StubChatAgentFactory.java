package com.apeloa.agent.chat;

import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/**
 * 测试用 {@link ChatAgentFactory}：不建真 ReActAgent、不连模型，事件由用例逐条手工投递。
 *
 * <p>一次 {@code stream()} 调用记一个 {@link Run}（含框架收到的载体 Msg），用例
 * {@link #awaitRun} 取出后按脚本 emit/complete/fail，从而精确控制 SSE 下行时序，
 * 并可核对 HITL 续跑消息里回传的 ConfirmResult。
 */
public class StubChatAgentFactory implements ChatAgentFactory {

    /** 一次 agent run。sink 用 unicast+buffer：订阅发生在 boundedElastic，先 emit 也不丢。 */
    public static final class Run {

        private final Msg message;
        private final Sinks.Many<AgentEvent> sink = Sinks.many().unicast().onBackpressureBuffer();

        private Run(Msg message) {
            this.message = message;
        }

        /** 框架侧收到的载体消息（HITL 续跑时其 metadata 带 ConfirmResults）。 */
        public Msg message() {
            return message;
        }

        public Run emit(AgentEvent... events) {
            for (AgentEvent event : events) {
                sink.tryEmitNext(event);
            }
            return this;
        }

        public void complete() {
            sink.tryEmitComplete();
        }

        public void fail(Throwable error) {
            sink.tryEmitError(error);
        }
    }

    private final BlockingQueue<Run> runs = new LinkedBlockingQueue<>();

    /** 所有 stub agent 共享的「框架上下文」脚本（M1-11 entries 落盘的权威源）。 */
    private volatile List<Msg> committedContext = List.of();

    @Override
    public ChatAgent create(String userId, String sessionId) {
        return new StubAgent();
    }

    /** 用例在 run 收尾前注入：run complete 时 AgentSession 会读它做增量落盘。 */
    public void setCommittedContext(List<Msg> context) {
        this.committedContext = List.copyOf(context);
    }

    /** 等下一次 run（POST 消息后由会话异步发起）。 */
    public Run awaitRun(Duration timeout) throws InterruptedException {
        Run run = runs.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (run == null) {
            throw new AssertionError("等不到 agent run");
        }
        return run;
    }

    /** 用例间清场：丢掉上一条用例没消费完的 run。 */
    public void reset() {
        runs.clear();
    }

    private final class StubAgent implements ChatAgent {

        private volatile Run current;

        @Override
        public Flux<AgentEvent> stream(Msg message) {
            Run run = new Run(message);
            current = run;
            runs.add(run);
            return run.sink.asFlux();
        }

        /** 对齐框架：中止表现为流以 InterruptedException 收尾。 */
        @Override
        public void interrupt() {
            Run run = current;
            if (run != null) {
                run.fail(new InterruptedException("已被中止"));
            }
        }

        /** 对齐 AgentScopeChatAgent：返回用例脚本化的上下文快照。 */
        @Override
        public List<Msg> committedContext() {
            return committedContext;
        }
    }
}
