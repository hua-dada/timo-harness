package com.apeloa.agent.chat.persist;

import com.apeloa.agent.chat.AgentSessionManager;
import com.apeloa.agent.chat.ChatPersistence;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * entries 落盘与回放（M1-11）。
 *
 * <p><b>增量同步</b>：每轮 run 结束（正常 / 异常 / HITL 暂停）把框架上下文的「新增尾巴」投影成
 * AgentMessage 追加进 {@code session_entries}，同一事务推进 {@code sessions.persisted_msgs} 游标、
 * 接长 {@code last_entry_id} 链、回填标题、刷新 {@code updated_at}。游标不动则下轮重试同一尾巴，
 * 事务原子性保证不重不漏。
 *
 * <p><b>全量回放</b>：按 {@code position} 顺序读出 payload 原文（存取零转换）交给
 * {@code messages_loaded}。
 *
 * <p>同步在 {@code AgentSession} 会话锁内被调（保证「游标推进」与「SSE 缓冲切片」对同一时间点
 * 成立，见 AgentSession.subscribe 的竞态说明），查询走 (session_id) 索引、行数为会话长度，
 * 锁内耗时毫秒级。
 */
@Service
public class ChatPersistenceService implements ChatPersistence {

    private static final Logger log = LoggerFactory.getLogger(ChatPersistenceService.class);

    private static final ObjectMapper JSON = new ObjectMapper();

    private final SessionMapper sessions;

    private final SessionEntryMapper entries;

    public ChatPersistenceService(SessionMapper sessions, SessionEntryMapper entries) {
        this.sessions = sessions;
        this.entries = entries;
    }

    /**
     * 把 {@code context}（框架上下文快照，权威源）中游标之后的部分落盘。
     */
    @Override
    @Transactional
    public SyncOutcome persistNewTurns(String sessionId, List<Msg> context) {
        UUID id = UUID.fromString(sessionId);
        SessionEntity row = sessions.selectById(id);
        if (row == null) {
            // 会话已删：entries 与 agent_states 已随 FK 级联清掉，别处也查不到，静默放弃。
            log.debug("会话行已不存在，跳过 entries 落盘：session={}", sessionId);
            return new SyncOutcome(-1, null);
        }
        int persisted = row.getPersistedMsgs();
        if (context.size() <= persisted) {
            touch(row);
            sessions.updateById(row);
            return new SyncOutcome(persisted, null);
        }
        List<Msg> fresh = context.subList(persisted, context.size());

        UUID parent = row.getLastEntryId();
        Msg firstUserMsg = null;
        List<SessionEntryEntity> rows = new ArrayList<>();
        for (Msg msg : fresh) {
            if (firstUserMsg == null && msg.getRole() == MsgRole.USER) {
                firstUserMsg = msg;
            }
            for (Map<String, Object> agentMessage : SessionEntryProjector.project(msg)) {
                UUID entryId = UUID.randomUUID();
                rows.add(
                        new SessionEntryEntity(
                                entryId,
                                id,
                                parent,
                                String.valueOf(agentMessage.get("role")),
                                writeJson(agentMessage)));
                parent = entryId;
            }
        }
        rows.forEach(entries::insert);

        row.setPersistedMsgs(context.size());
        row.setLastEntryId(parent);
        String newTitle = null;
        if ((row.getTitle() == null
                        || row.getTitle().equals(AgentSessionManager.DEFAULT_TITLE))
                && firstUserMsg != null) {
            String title = SessionEntryProjector.titleOf(firstUserMsg);
            if (title != null) {
                row.setTitle(title);
                newTitle = title;
            }
        }
        touch(row);
        sessions.updateById(row);
        log.debug(
                "entries 已落盘：session={} 新增Msg={} 新增行={} 游标={}",
                sessionId,
                fresh.size(),
                rows.size(),
                context.size());
        return new SyncOutcome(context.size(), newTitle);
    }

    /** 会话历史（前端 AgentMessage 形状，按落盘顺序），供 {@code messages_loaded} 全量重建。 */
    @Override
    @Transactional(readOnly = true)
    public List<Object> loadHistory(String sessionId) {
        List<Object> history = new ArrayList<>();
        for (SessionEntryEntity row : entries.listOrdered(UUID.fromString(sessionId))) {
            try {
                history.add(JSON.readValue(row.getPayload(), Object.class));
            } catch (Exception e) {
                // 单行损坏不炸整段历史（append-only 表理论上不会发生）；跳过并留痕。
                log.warn(
                        "历史行损坏已跳过：session={} entry={} 原因={}",
                        sessionId,
                        row.getId(),
                        e.toString());
            }
        }
        return history;
    }

    /** 会话是否存在且属于该用户（Manager 重建路径用；不存在/异主返回 null）。 */
    @Transactional(readOnly = true)
    public SessionEntity findOwned(String userId, String sessionId) {
        UUID id;
        try {
            id = UUID.fromString(sessionId);
        } catch (IllegalArgumentException e) {
            return null;
        }
        SessionEntity row = sessions.selectById(id);
        return row != null && row.getUserId().equals(userId) ? row : null;
    }

    /** 当前用户的会话行，新的在前（updated_at desc）。 */
    @Transactional(readOnly = true)
    public List<SessionEntity> listByUser(String userId) {
        return sessions.selectList(
                new LambdaQueryWrapper<SessionEntity>()
                        .eq(SessionEntity::getUserId, userId)
                        .orderByDesc(SessionEntity::getUpdatedAt));
    }

    private static void touch(SessionEntity row) {
        row.setUpdatedAt(OffsetDateTime.now());
    }

    private static String writeJson(Map<String, Object> agentMessage) {
        try {
            return JSON.writeValueAsString(agentMessage);
        } catch (Exception e) {
            // LinkedHashMap 全是基础类型，序列化不可能失败；防御性兜底。
            throw new IllegalStateException("AgentMessage 序列化失败", e);
        }
    }
}
