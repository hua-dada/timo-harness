package com.apeloa.agent.chat;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * M1-6 会话事件缓冲：每会话有界环形队列，给每条 {@link ChatDelta} 分配会话内单调递增 seq，
 * 支撑 SSE {@code Last-Event-Id} 断线续传（spec：框架事件流不可重放，续传缓冲自建）。
 *
 * <p>语义：
 * <ul>
 *   <li>{@link #append}：seq 从 1 起单调分配；队列满则淘汰最旧（AgentScope 流不可回放，
 *       超窗部分永久丢失，调用方据 {@link #firstSeq()} 判定走全量重建路径）。</li>
 *   <li>{@link #replayAfter}：Last-Event-Id 在窗口内 → 返回其后全部事件；等于/超过
 *       {@link #lastSeq()} → 空列表（直接挂实时流）；早于 {@code firstSeq} →
 *       {@link Optional#empty()}（调用方发 session_info+messages_loaded 全量重建）。</li>
 * </ul>
 *
 * <p>线程模型：所有方法 synchronized——事件产出是单流（每会话同一时刻仅一个 agent run），
 * 竞争只来自 SSE 订阅方的读，临界区均为内存操作，锁开销可忽略。
 */
public final class SessionEventBuffer {

    /** 缓冲内事件 + 其 seq（SSE 帧的 id 字段）。 */
    public record Sequenced(long seq, ChatDelta delta) {
    }

    private final ArrayDeque<Sequenced> queue;
    private final int capacity;
    private long lastSeq;

    public SessionEventBuffer(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity 必须 ≥ 1：" + capacity);
        }
        this.queue = new ArrayDeque<>(Math.min(capacity, 1024));
        this.capacity = capacity;
        this.lastSeq = 0;
    }

    /** 追加事件并分配 seq。 */
    public synchronized Sequenced append(ChatDelta delta) {
        Sequenced event = new Sequenced(++lastSeq, delta);
        if (queue.size() == capacity) {
            queue.pollFirst();
        }
        queue.addLast(event);
        return event;
    }

    /** 当前窗口最旧事件的 seq（空缓冲返回 1）。 */
    public synchronized long firstSeq() {
        Sequenced first = queue.peekFirst();
        return first == null ? 1 : first.seq();
    }

    /** 最新事件的 seq（空缓冲返回 0）。 */
    public synchronized long lastSeq() {
        return lastSeq;
    }

    /**
     * 补发 {@code seq > lastEventId} 的全部缓冲事件。
     *
     * @return 空Optional = Last-Event-Id 早于窗口起点（调用方走全量重建）；
     *         非空列表（可为空）= 增量补发集，直接挂实时流。
     */
    public synchronized Optional<List<Sequenced>> replayAfter(long lastEventId) {
        if (lastEventId < firstSeq() - 1) {
            return Optional.empty();
        }
        List<Sequenced> replay = new ArrayList<>();
        for (Sequenced e : queue) {
            if (e.seq() > lastEventId) {
                replay.add(e);
            }
        }
        return Optional.of(replay);
    }

    /** 全量快照（调试/测试用）。 */
    public synchronized List<Sequenced> snapshot() {
        return new ArrayList<>(queue);
    }
}
