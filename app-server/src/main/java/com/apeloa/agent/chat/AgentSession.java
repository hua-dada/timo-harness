package com.apeloa.agent.chat;

import com.apeloa.agent.chat.SessionEventBuffer.Sequenced;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.UserMessage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.scheduler.Schedulers;

/**
 * 一个聊天会话：串行跑 Agent、把事件投影成 {@link ChatDelta} 落缓冲、广播给各 SSE 连接，
 * 并承载 HITL 暂停/续跑（M1-6）。
 *
 * <p><b>状态机</b>：
 * <pre>
 *   IDLE ──send()──▶ RUNNING ──流正常收尾（无待确认）──▶ IDLE
 *                      │                          └──有待确认──▶ AWAITING_CONFIRM
 *                      └──流异常/中止──▶ error + agent_end ──▶ IDLE
 *   AWAITING_CONFIRM ──全部 resolveConfirm()──▶ RUNNING（带 ConfirmResults 续跑同一回合）
 * </pre>
 * RUNNING / AWAITING_CONFIRM 下再来 prompt 一律 {@link SessionBusyException}（对齐源项目
 * 由 pi 子进程串行化保证的「一次一条」）。
 *
 * <p><b>线程模型</b>：所有改状态的方法与 {@link #emit} 同步在会话实例上——事件来自 Reactor
 * 线程、prompt/HITL/订阅来自 HTTP 线程。临界区只有内存操作与非阻塞入队，慢客户端不会拖住
 * agent 线程（积压由 {@link SessionSubscription} 自行作废）。
 */
public final class AgentSession implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AgentSession.class);

    /** 思考档位：M1-6 不开放切换（无对应 AgentScope 能力），握手时回固定值。 */
    private static final String THINKING_LEVEL = "medium";

    public enum State {
        IDLE,
        RUNNING,
        AWAITING_CONFIRM
    }

    private final String sessionId;
    private final String userId;
    /** 会话名：建会话值或首条用户消息回填的标题（M1-11 落盘后可变，见 syncEntries）。 */
    private volatile String name;
    private final ChatAgent agent;
    private final SessionEventBuffer buffer;
    private final ChatPersistence persistence;
    private final List<SessionSubscription> subscriptions = new CopyOnWriteArrayList<>();

    /** 待人工确认的工具调用（requestId = toolCallId），按 ui_request 发出顺序。 */
    private final Map<String, ToolUseBlock> pendingConfirms = new LinkedHashMap<>();

    /** 已应答但尚未凑齐本轮全部确认的决定，凑齐后一次性随续跑消息回传框架。 */
    private final Map<String, ConfirmResult> confirmDecisions = new LinkedHashMap<>();

    private final long createdAt = System.currentTimeMillis();
    private volatile long lastActiveAt = System.currentTimeMillis();
    private volatile State state = State.IDLE;
    private volatile boolean closed;
    private boolean aborting;
    private Disposable run;

    /** 最近一次成功落盘时的缓冲 seq：全量重建回放只补这之后的「未落盘尾巴」。 */
    private volatile long persistedSeq;

    public AgentSession(
            String sessionId,
            String userId,
            String name,
            ChatAgent agent,
            int bufferSize,
            ChatPersistence persistence) {
        this.sessionId = sessionId;
        this.userId = userId;
        this.name = name;
        this.agent = agent;
        this.buffer = new SessionEventBuffer(bufferSize);
        this.persistence = persistence;
    }

    public String sessionId() {
        return sessionId;
    }

    public String userId() {
        return userId;
    }

    public String name() {
        return name;
    }

    public State state() {
        return state;
    }

    public long createdAt() {
        return createdAt;
    }

    public long lastActiveAt() {
        return lastActiveAt;
    }

    /** SSE 握手首帧内容。 */
    public ChatDelta.SessionInfo sessionInfo() {
        return ChatDelta.SessionInfo.of(sessionId, name);
    }

    public ChatDelta.ThinkingLevel thinkingLevel() {
        return ChatDelta.ThinkingLevel.of(THINKING_LEVEL);
    }

    /** 是否还有 SSE 连接挂着（决定能否被空闲驱逐）。 */
    public boolean hasSubscribers() {
        return !subscriptions.isEmpty();
    }

    /** 待确认工具调用数（诊断/测试用）。 */
    public synchronized int pendingConfirmCount() {
        return pendingConfirms.size();
    }

    /** 该 requestId 是否是本会话待确认的（HITL 路由据此在用户会话里定位目标会话）。 */
    public synchronized boolean hasPendingConfirm(String requestId) {
        return pendingConfirms.containsKey(requestId);
    }

    /**
     * 上行 prompt：立即回 {@code prompt_accepted} 并起一次 run。
     *
     * @throws SessionBusyException 会话不在 IDLE（在跑 / 等确认）
     */
    public synchronized void send(String text) {
        ensureOpen();
        if (state != State.IDLE) {
            throw new SessionBusyException(state);
        }
        state = State.RUNNING;
        aborting = false;
        emit(ChatDelta.PromptAccepted.of());
        startRun(UserMessage.builder().textContent(text).build());
    }

    /**
     * 中止：RUNNING 直接打断框架在途执行；AWAITING_CONFIRM 则把全部待确认按拒绝应答并续跑，
     * 否则 ASKING 状态留在 AgentState 里，下一条 prompt 会被框架判为「挂起未应答」而失败。
     */
    public synchronized void abort() {
        ensureOpen();
        switch (state) {
            case RUNNING -> {
                aborting = true;
                agent.interrupt();
                touch();
            }
            case AWAITING_CONFIRM -> {
                for (String requestId : List.copyOf(pendingConfirms.keySet())) {
                    reject(requestId);
                }
                resume();
            }
            case IDLE -> log.debug("会话 {} 无在途任务，abort 忽略", sessionId);
        }
    }

    /**
     * HITL 应答。{@code newArgs} 非空即改参执行（框架用回传的 ToolUseBlock 原地替换 ASKING 块）。
     * 本轮全部确认凑齐后才续跑——框架校验允许子集应答，但剩下的仍会停在 ASKING。
     *
     * @throws UnknownConfirmRequestException requestId 不在待确认表
     */
    public synchronized void resolveConfirm(String requestId, boolean approved, Map<String, Object> newArgs) {
        ensureOpen();
        if (state != State.AWAITING_CONFIRM || !pendingConfirms.containsKey(requestId)) {
            throw new UnknownConfirmRequestException(requestId);
        }
        if (approved) {
            ToolUseBlock call = pendingConfirms.remove(requestId);
            ToolUseBlock decided =
                    newArgs == null || newArgs.isEmpty()
                            ? call
                            : ToolUseBlock.builder()
                                    .id(call.getId())
                                    .name(call.getName())
                                    .input(newArgs)
                                    .build();
            confirmDecisions.put(requestId, new ConfirmResult(true, decided));
        } else {
            reject(requestId);
        }
        if (pendingConfirms.isEmpty()) {
            resume();
        } else {
            touch();
        }
    }

    /**
     * 建立一条 SSE 订阅：补发与注册在同一临界区内完成，实时事件不会从缝隙漏过。
     *
     * <p><b>全量重建切片（M1-11）</b>：messages_loaded（DB 历史）与缓冲「未落盘尾巴」
     * （seq &gt; {@link #persistedSeq}）必须在同一会话锁内取样——落盘（onRunComplete）也持同一
     * 把锁，否则「查历史→跑完落盘→切尾巴」交错会让同一回合在两处各出现一次（前端文本翻倍）。
     * DB 读因此在锁内执行：查询走 (session_id) 索引、行数为会话长度，毫秒级。
     */
    public synchronized SessionSubscription subscribe(Long lastEventId) {
        ensureOpen();
        Optional<List<Sequenced>> replay =
                lastEventId == null ? Optional.empty() : buffer.replayAfter(lastEventId);
        boolean fullRebuild = replay.isEmpty();
        List<Object> history = List.of();
        List<Sequenced> backlog = replay.orElseGet(List::of);
        if (fullRebuild) {
            history = persistence.loadHistory(sessionId);
            backlog = new ArrayList<>();
            for (Sequenced event : buffer.snapshot()) {
                if (event.seq() > persistedSeq) {
                    backlog.add(event);
                }
            }
        }
        SessionSubscription subscription =
                new SessionSubscription(this, fullRebuild, history, backlog);
        subscriptions.add(subscription);
        touch();
        return subscription;
    }

    void unsubscribe(SessionSubscription subscription) {
        subscriptions.remove(subscription);
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (run != null && !run.isDisposed()) {
            run.dispose();
        }
        for (SessionSubscription subscription : subscriptions) {
            subscription.terminate();
        }
        subscriptions.clear();
        pendingConfirms.clear();
        confirmDecisions.clear();
        state = State.IDLE;
    }

    /** 拒绝一条确认，并自行补发 {@code tool_end}：框架的拒绝结果直接写进上下文、不发事件。 */
    private void reject(String requestId) {
        ToolUseBlock call = pendingConfirms.remove(requestId);
        confirmDecisions.put(requestId, new ConfirmResult(false, call));
        emit(ChatDelta.ToolEnd.of(call.getId(), call.getName(), true, "用户已拒绝执行"));
    }

    /** 带 ConfirmResults 续跑同一回合（框架从 ASKING 态恢复执行，不把载体消息写进上下文）。 */
    private void resume() {
        List<ConfirmResult> results = List.copyOf(confirmDecisions.values());
        confirmDecisions.clear();
        state = State.RUNNING;
        aborting = false;
        startRun(
                UserMessage.builder()
                        .metadata(Map.of(Msg.METADATA_CONFIRM_RESULTS, results))
                        .build());
    }

    /**
     * 起一次 run。{@link AgentEventMapper} 每 run 一个实例（按 toolCallId 累积参数/结果片段），
     * 续跑时新建即可：已发出的 tool_args 不需要重放，工具结果的 Start/Delta/End 会在续跑流里重来。
     */
    private void startRun(Msg message) {
        AgentEventMapper mapper = new AgentEventMapper();
        touch();
        run =
                agent.stream(message)
                        .subscribeOn(Schedulers.boundedElastic())
                        .subscribe(
                                event -> onEvent(mapper, event),
                                error -> onRunError(error),
                                () -> onRunComplete(mapper));
    }

    private synchronized void onEvent(AgentEventMapper mapper, AgentEvent event) {
        for (ChatDelta delta : mapper.map(event)) {
            emit(delta);
        }
    }

    private synchronized void onRunComplete(AgentEventMapper mapper) {
        State next = State.IDLE;
        if (mapper.awaitingConfirm()) {
            pendingConfirms.clear();
            for (ToolUseBlock call : mapper.confirmToolCalls()) {
                pendingConfirms.put(call.getId(), call);
            }
            next = State.AWAITING_CONFIRM;
            log.debug("会话 {} 暂停等待确认：{}", sessionId, pendingConfirms.keySet());
        }
        // HITL 暂停也落盘：半程 assistant（含 ASKING toolCall）照投影，重启后历史可见。
        syncEntries();
        touch();
        // 状态最后翻（volatile 写）：只读观察者（列表轮询）看到的 IDLE 即代表本回合已提交。
        // send/resolveConfirm 持同一会话锁，本来就等整个收尾方法返回，先后对它们无差别。
        state = next;
    }

    /**
     * 流异常收尾：补 {@code error} + {@code agent_end}，否则前端会永远停在 streaming。
     * 中止路径框架抛 InterruptedException，此处换成可读的「已中止」。
     */
    private synchronized void onRunError(Throwable error) {
        if (aborting) {
            log.debug("会话 {} 已中止", sessionId);
            emit(ChatDelta.Error.of("已中止"));
        } else {
            log.warn("会话 {} 执行失败：{}", sessionId, error.toString());
            emit(ChatDelta.Error.of(readable(error)));
        }
        emit(ChatDelta.AgentEnd.of());
        // 中止/异常的半程上下文同样落盘（游标不前进就整段重试，幂等）。
        syncEntries();
        touch();
        // 与 onRunComplete 同序：落盘后才翻 IDLE。
        state = State.IDLE;
        aborting = false;
    }

    /**
     * run 收尾的增量落盘（在会话锁内）：以 {@link ChatAgent#committedContext()} 为权威源，
     * 游标由 DB 事务推进。失败只记日志——下轮结束会重投同一段尾巴，事务保证不重不漏；
     * 此时 persistedSeq 不动，全量重建仍按旧切片回放缓冲（宁可重复展示也不丢）。
     */
    private void syncEntries() {
        try {
            List<Msg> context = agent.committedContext();
            if (context.isEmpty()) {
                return;
            }
            ChatPersistence.SyncOutcome outcome = persistence.persistNewTurns(sessionId, context);
            if (outcome.cursor() < 0) {
                return;
            }
            // 此刻持锁：lastSeq 恰覆盖本回合已发出的全部事件（含 agent_end），切片无重叠无缝隙。
            persistedSeq = buffer.lastSeq();
            if (outcome.title() != null) {
                name = outcome.title();
            }
        } catch (RuntimeException e) {
            log.warn("会话 {} entries 落盘失败（下轮结束重试）：{}", sessionId, e.toString());
        }
    }

    /** 落缓冲分配 seq → 广播给各连接（非阻塞入队，慢连接自行作废）。 */
    private void emit(ChatDelta delta) {
        Sequenced event = buffer.append(delta);
        for (SessionSubscription subscription : subscriptions) {
            subscription.offer(event);
        }
    }

    private void touch() {
        lastActiveAt = System.currentTimeMillis();
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("会话已关闭：" + sessionId);
        }
    }

    private static String readable(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getMessage();
        return message == null || message.isBlank() ? root.getClass().getSimpleName() : message;
    }
}
