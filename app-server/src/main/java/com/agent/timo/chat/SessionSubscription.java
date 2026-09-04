package com.agent.timo.chat;

import com.agent.timo.chat.SessionEventBuffer.Sequenced;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 一条 SSE 连接的下行队列。每连接独占一个队列、由唯一的写线程 {@link #poll} 后写帧，
 * 保证同一连接内事件严格有序——这是不让 agent 线程直接回调 emitter 的原因：
 * 补发（HTTP 线程）与实时流（agent 线程）若同时写同一个 emitter 会乱序。
 *
 * <p>队满（客户端读得太慢）即 {@link #overflowed()} 并作废本连接：让浏览器带
 * {@code Last-Event-Id} 重连、走缓冲补发，比无界堆积更安全。
 */
public final class SessionSubscription implements AutoCloseable {

    /** 单连接积压上限；按 2000 条缓冲窗同量级取整。 */
    private static final int CAPACITY = 2048;

    private final AgentSession owner;
    private final boolean fullRebuild;
    /** 全量重建时的 DB 历史（messages_loaded 载荷）；非全量重建为空列表。 */
    private final List<Object> rebuildHistory;
    private final BlockingQueue<Sequenced> queue = new ArrayBlockingQueue<>(CAPACITY);

    private volatile boolean closed;
    private volatile boolean overflowed;

    SessionSubscription(
            AgentSession owner,
            boolean fullRebuild,
            List<Object> rebuildHistory,
            List<Sequenced> backlog) {
        this.owner = owner;
        this.fullRebuild = fullRebuild;
        this.rebuildHistory = List.copyOf(rebuildHistory);
        for (Sequenced event : backlog) {
            if (!queue.offer(event)) {
                overflowed = true;
                break;
            }
        }
    }

    /** true = 本次连接需先发 messages_loaded 全量重建（Last-Event-Id 缺失或已超出缓冲窗）。 */
    public boolean fullRebuild() {
        return fullRebuild;
    }

    /** 全量重建载荷（与补发尾巴同一临界区取的 DB 历史切片）。 */
    public List<Object> rebuildHistory() {
        return rebuildHistory;
    }

    public boolean overflowed() {
        return overflowed;
    }

    public boolean closed() {
        return closed;
    }

    /** 取下一条事件；超时返回 null（调用方据此发注释心跳保活）。 */
    public Sequenced poll(long timeoutMillis) throws InterruptedException {
        return queue.poll(timeoutMillis, TimeUnit.MILLISECONDS);
    }

    /** 由 {@link AgentSession} 持锁调用：非阻塞入队，队满即作废本连接。 */
    void offer(Sequenced event) {
        if (closed) {
            return;
        }
        if (!queue.offer(event)) {
            overflowed = true;
            closed = true;
        }
    }

    /** 会话侧终止（驱逐 / 关闭）：只置标志，由写线程在下次 poll 超时后收尾。 */
    void terminate() {
        closed = true;
    }

    @Override
    public void close() {
        closed = true;
        owner.unsubscribe(this);
    }
}
