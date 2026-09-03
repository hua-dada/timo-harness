package com.apeloa.agent.chat.replay;

import static org.assertj.core.api.Assertions.assertThat;

import com.apeloa.agent.chat.persist.AgentStateMapper;
import com.apeloa.agent.chat.persist.DbAgentStateStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.UUID;

/**
 * PoC-A / M2-7 的 fork 手法：直接在 {@code agent_states.payload} 这一份整档上做复制与删尾。
 *
 * <p>为什么是整档而不是重放 entries：{@code session_entries} 是前端有损投影（见
 * {@link com.apeloa.agent.chat.persist.SessionEntryProjector}，反序列化回 {@code Msg} 直接失败），
 * 模型上下文的唯一权威是 {@code agent_states}。
 *
 * <p><b>内嵌 {@code session_id} 必须改写</b>：优雅停机的 state saver 用的是文档里的
 * {@code getSessionId()}（ReActAgent#bindStateSaver），不是槽位 key——不改写，停机时会把 fork
 * 会话的状态写回源会话行。
 */
final class StateFork {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final AgentStateMapper mapper;
    private final String userId;

    StateFork(AgentStateMapper mapper, String userId) {
        this.mapper = mapper;
        this.userId = userId;
    }

    /** 整档复制（clone / 全量 fork）：历史全带走，新会话可继续追问。 */
    String whole(String sourceSessionId, String targetSessionId) {
        ObjectNode doc = doc(sourceSessionId);
        doc.put("session_id", targetSessionId);
        save(targetSessionId, doc);
        return targetSessionId;
    }

    /** 前缀截断（从某条 user 消息重新生成）：只保留前 {@code keep} 条 context。 */
    String truncated(String sourceSessionId, String targetSessionId, int keep) {
        ObjectNode doc = doc(sourceSessionId);
        doc.put("session_id", targetSessionId);
        ArrayNode kept = JSON.createArrayNode();
        for (int i = 0; i < keep; i++) {
            kept.add(doc.get("context").get(i));
        }
        doc.set("context", kept);
        save(targetSessionId, doc);
        return targetSessionId;
    }

    /** 落盘的整档原文（断言已落盘）。 */
    String raw(String sessionId) {
        String payload =
                mapper.findPayload(
                        userId, UUID.fromString(sessionId), DbAgentStateStore.AGENT_STATE_KEY);
        assertThat(payload).as("会话 %s 的 agent_state 应已落盘", sessionId).isNotNull();
        return payload;
    }

    ObjectNode doc(String sessionId) {
        return (ObjectNode) parse(raw(sessionId));
    }

    private void save(String sessionId, ObjectNode doc) {
        mapper.upsert(
                userId, UUID.fromString(sessionId), DbAgentStateStore.AGENT_STATE_KEY, doc.toString());
    }

    static JsonNode parse(String json) {
        try {
            return JSON.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException("状态文档解析失败", e);
        }
    }

    static String stringify(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("序列化失败", e);
        }
    }
}
