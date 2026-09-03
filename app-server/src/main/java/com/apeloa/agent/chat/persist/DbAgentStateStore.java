package com.apeloa.agent.chat.persist;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.State;
import io.agentscope.core.util.JsonUtils;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * {@link AgentStateStore} 的 PG 实现（M1-11）：把 {@code AgentState} 整档序列化成单个 JSON
 * 文档存 {@code agent_states.payload}，键 (userId, sessionId, stateKey)。
 *
 * <p>框架用法（ReActAgent 源码已核实）：配了 store 后<b>每次 call 开头重载、结尾整档覆盖写</b>
 * （key 固定 {@code "agent_state"}，异步落 boundedElastic）。因此驱逐/重启后的模型上下文恢复
 * 是框架行为——本类只做无状态的存取，不需要缓存。
 *
 * <p>序列化走框架全局 {@code JsonUtils.getJsonCodec()}（与自带 JsonFileAgentStateStore 同构），
 * 保证框架写回的文档我们读得出、反之亦然。List 形态存 JSON 数组（JSONL 惯例只适合文件，
 * jsonb 列不收多行拼接）。
 *
 * <p>{@code sessionId} 必须是本应用生成的 UUID（表列类型即 UUID）；{@code userId} 为 null 时
 * 以空串落库（匿名单租户），与框架 slotKey 的 null 归一一致。
 */
@Component
public class DbAgentStateStore implements AgentStateStore {

    private static final Logger log = LoggerFactory.getLogger(DbAgentStateStore.class);

    /** 框架固定状态键；仅日志与排查用。 */
    public static final String AGENT_STATE_KEY = "agent_state";

    private final AgentStateMapper mapper;

    public DbAgentStateStore(AgentStateMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void save(String userId, String sessionId, String key, State value) {
        mapper.upsert(user(userId), session(sessionId), key, JsonUtils.getJsonCodec().toJson(value));
    }

    @Override
    public void save(String userId, String sessionId, String key, List<? extends State> values) {
        // 列表形态存 JSON 数组：框架 JsonFileAgentStateStore 的 JSONL 惯例只适合文件，
        // 本表 payload 是 jsonb 列，多行拼接不是合法 JSON（PG 会拒收）。
        StringBuilder json = new StringBuilder("[");
        for (State value : values) {
            if (json.length() > 1) {
                json.append(',');
            }
            json.append(JsonUtils.getJsonCodec().toJson(value));
        }
        json.append(']');
        mapper.upsert(user(userId), session(sessionId), key, json.toString());
    }

    @Override
    public <T extends State> Optional<T> get(String userId, String sessionId, String key, Class<T> type) {
        String payload = mapper.findPayload(user(userId), session(sessionId), key);
        if (payload == null || payload.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(JsonUtils.getJsonCodec().fromJson(payload, type));
        } catch (RuntimeException e) {
            // 框架 loadOrCreate 对损坏状态会吞异常重建；这里对齐，只留日志。
            log.warn("状态文档反序列化失败，视为不存在：key={} 原因={}", key, e.toString());
            return Optional.empty();
        }
    }

    @Override
    public <T extends State> List<T> getList(
            String userId, String sessionId, String key, Class<T> itemType) {
        String payload = mapper.findPayload(user(userId), session(sessionId), key);
        if (payload == null || payload.isBlank()) {
            return List.of();
        }
        try {
            @SuppressWarnings("unchecked")
            T[] items = (T[]) JsonUtils.getJsonCodec().fromJson(payload, itemType.arrayType());
            return items == null ? List.of() : List.of(items);
        } catch (RuntimeException e) {
            // 与 get 同口径：损坏文档按空处理，不阻断框架调用链。
            log.warn("状态列表反序列化失败，按空处理：key={} 原因={}", key, e.toString());
            return List.of();
        }
    }

    @Override
    public boolean exists(String userId, String sessionId) {
        return !mapper
                .selectList(
                        new LambdaQueryWrapper<AgentStateEntity>()
                                .eq(AgentStateEntity::getUserId, user(userId))
                                .eq(AgentStateEntity::getSessionId, session(sessionId)))
                .isEmpty();
    }

    @Override
    public void delete(String userId, String sessionId) {
        mapper.delete(
                new LambdaQueryWrapper<AgentStateEntity>()
                        .eq(AgentStateEntity::getUserId, user(userId))
                        .eq(AgentStateEntity::getSessionId, session(sessionId)));
    }

    @Override
    public void delete(String userId, String sessionId, String key) {
        mapper.delete(
                new LambdaQueryWrapper<AgentStateEntity>()
                        .eq(AgentStateEntity::getUserId, user(userId))
                        .eq(AgentStateEntity::getSessionId, session(sessionId))
                        .eq(AgentStateEntity::getStateKey, key));
    }

    @Override
    public Set<String> listSessionIds(String userId) {
        Set<String> ids = new HashSet<>();
        mapper.selectList(
                        new LambdaQueryWrapper<AgentStateEntity>()
                                .eq(AgentStateEntity::getUserId, user(userId)))
                .forEach(entity -> ids.add(entity.getSessionId().toString()));
        return ids;
    }

    private static String user(String userId) {
        return userId == null ? "" : userId;
    }

    private static UUID session(String sessionId) {
        return UUID.fromString(sessionId == null ? "" : sessionId);
    }
}
