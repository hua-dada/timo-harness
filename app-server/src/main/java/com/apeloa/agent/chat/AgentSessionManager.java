package com.apeloa.agent.chat;

import com.apeloa.agent.chat.persist.ChatPersistenceService;
import com.apeloa.agent.chat.persist.SessionEntity;
import com.apeloa.agent.chat.persist.SessionMapper;
import jakarta.annotation.PreDestroy;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 会话表（M1-11 起以 DB 为权威）：{@code sessions} 行持久存在，内存里的 {@link AgentSession}
 * 只是运行时载体——按 sessionId 索引、按 userId 归属校验，miss 时从库里重建
 * （SSE 全量重建随之取回真实历史，模型上下文由框架 AgentStateStore 自动重载）。
 *
 * <p>空闲驱逐（tech design §2.2A）：IDLE、无 SSE 连接、且静默超过 {@code app.chat.idle-minutes}
 * 才回收——挂着连接的会话不驱逐（浏览器标签开着就算活跃），在跑或等确认的会话不驱逐（否则
 * 打断在途任务）。驱逐只清内存：entries 已在每轮 run 收尾落盘，agent_state 由框架在 call
 * 结尾落盘，重建即可恢复。
 */
@Component
public class AgentSessionManager {

    private static final Logger log = LoggerFactory.getLogger(AgentSessionManager.class);

    /** 驱逐扫描周期。 */
    private static final long SWEEP_SECONDS = 60;

    /** 建会话默认名；首条用户消息回填标题时以其判断「尚未命名」（见 ChatPersistenceService）。 */
    public static final String DEFAULT_TITLE = "新会话";

    private final Map<String, AgentSession> sessions = new ConcurrentHashMap<>();
    private final ChatAgentFactory agentFactory;
    private final ChatPersistenceService persistence;
    private final SessionMapper sessionMapper;
    private final int bufferSize;
    private final long idleMillis;
    private final ScheduledExecutorService sweeper =
            Executors.newSingleThreadScheduledExecutor(
                    runnable -> {
                        Thread thread = new Thread(runnable, "chat-session-sweeper");
                        thread.setDaemon(true);
                        return thread;
                    });

    public AgentSessionManager(
            ChatAgentFactory agentFactory,
            ChatPersistenceService persistence,
            SessionMapper sessionMapper,
            @Value("${app.chat.buffer-size:2000}") int bufferSize,
            @Value("${app.chat.idle-minutes:30}") long idleMinutes) {
        this.agentFactory = agentFactory;
        this.persistence = persistence;
        this.sessionMapper = sessionMapper;
        this.bufferSize = bufferSize;
        this.idleMillis = TimeUnit.MINUTES.toMillis(idleMinutes);
        sweeper.scheduleWithFixedDelay(this::evictIdle, SWEEP_SECONDS, SWEEP_SECONDS, TimeUnit.SECONDS);
    }

    /** 建会话（先建 Agent 再落行：Model 未配置时 503，不留孤儿行）。 */
    public AgentSession create(String userId, String name) {
        String sessionId = UUID.randomUUID().toString();
        ChatAgent agent = agentFactory.create(userId, sessionId);
        String title = name == null || name.isBlank() ? DEFAULT_TITLE : name.strip();

        SessionEntity row = new SessionEntity();
        row.setId(UUID.fromString(sessionId));
        row.setUserId(userId);
        row.setTitle(title);
        OffsetDateTime now = OffsetDateTime.now();
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        sessionMapper.insert(row);

        AgentSession session =
                new AgentSession(sessionId, userId, title, agent, bufferSize, persistence);
        sessions.put(sessionId, session);
        log.info("会话已创建：session={} user={}", sessionId, userId);
        return session;
    }

    /**
     * 按归属取会话；内存 miss 时从 DB 重建（重启/驱逐后的恢复路径）。不存在或属于他人一律
     * 404（不泄露存在性）。重建要建 Agent：Model 未配置时同样 503（会话列表仍可读）。
     */
    public AgentSession require(String userId, String sessionId) {
        AgentSession live = sessions.get(sessionId);
        if (live != null) {
            if (!live.userId().equals(userId)) {
                throw new SessionNotFoundException(sessionId);
            }
            return live;
        }
        SessionEntity row = persistence.findOwned(userId, sessionId);
        if (row == null) {
            throw new SessionNotFoundException(sessionId);
        }
        // 并发重建竞争：输家关掉自己刚建的 Agent，用赢家那份（sessionId 全局唯一，异主已在上面 404）。
        AgentSession rebuilt =
                new AgentSession(
                        sessionId,
                        userId,
                        row.getTitle() == null ? DEFAULT_TITLE : row.getTitle(),
                        agentFactory.create(userId, sessionId),
                        bufferSize,
                        persistence);
        AgentSession winner = sessions.putIfAbsent(sessionId, rebuilt);
        if (winner != null) {
            rebuilt.close();
            return winner;
        }
        log.info("会话已从库重建：session={} user={}", sessionId, userId);
        return rebuilt;
    }

    /** 当前用户的会话概要（DB 为权威，updated_at 倒序）；内存态只贡献实时 state。 */
    public List<SessionSummary> list(String userId) {
        List<SessionSummary> out = new ArrayList<>();
        for (SessionEntity row : persistence.listByUser(userId)) {
            AgentSession live = sessions.get(String.valueOf(row.getId()));
            String state =
                    live != null && live.userId().equals(userId)
                            ? live.state().name().toLowerCase(java.util.Locale.ROOT)
                            : "idle";
            String title = row.getTitle() == null ? DEFAULT_TITLE : row.getTitle();
            out.add(
                    new SessionSummary(
                            String.valueOf(row.getId()),
                            title,
                            state,
                            toEpochMillis(row.getCreatedAt())));
        }
        return out;
    }

    /**
     * 按 HITL requestId 在该用户的会话里定位目标会话：源协议的 ui-response 只带 requestId
     * （WS 天然绑定会话），SSE 下改由服务端反查，且只在归属用户的会话里找。
     * 挂起确认是运行时状态，只查内存（重启即失效，M1-8 再议持久化）。
     */
    public Optional<AgentSession> findByConfirmRequest(String userId, String requestId) {
        return sessions.values().stream()
                .filter(session -> session.userId().equals(userId))
                .filter(session -> session.hasPendingConfirm(requestId))
                .findFirst();
    }

    /** 删会话：内存关闭 + DB 删行（entries / agent_states 随 FK 级联）。 */
    public void remove(String userId, String sessionId) {
        AgentSession session = require(userId, sessionId);
        sessions.remove(sessionId);
        session.close();
        sessionMapper.deleteById(UUID.fromString(sessionId));
        log.info("会话已删除：session={} user={}", sessionId, userId);
    }

    private void evictIdle() {
        long deadline = System.currentTimeMillis() - idleMillis;
        try {
            for (AgentSession session : sessions.values()) {
                if (session.state() == AgentSession.State.IDLE
                        && !session.hasSubscribers()
                        && session.lastActiveAt() < deadline
                        && sessions.remove(session.sessionId(), session)) {
                    session.close();
                    log.info("会话空闲驱逐：session={} user={}", session.sessionId(), session.userId());
                }
            }
        } catch (RuntimeException e) {
            // 抛出会让 scheduleWithFixedDelay 静默停掉后续扫描，只记日志。
            log.warn("会话驱逐扫描失败：{}", e.toString());
        }
    }

    private static long toEpochMillis(OffsetDateTime time) {
        return time == null ? 0L : time.toInstant().toEpochMilli();
    }

    @PreDestroy
    void shutdown() {
        sweeper.shutdownNow();
        sessions.values().forEach(AgentSession::close);
        sessions.clear();
    }
}
