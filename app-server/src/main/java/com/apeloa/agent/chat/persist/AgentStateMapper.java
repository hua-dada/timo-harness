package com.apeloa.agent.chat.persist;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.UUID;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * {@code agent_states} 访问。整档覆盖写语义（框架每次 call 结尾全量重存）用
 * {@link #upsert} 一条语句完成；其余按复合主键三列查询。
 */
@Mapper
public interface AgentStateMapper extends BaseMapper<AgentStateEntity> {

    /** 整档覆盖：无则插、有则换 payload 并刷新 updated_at。 */
    @Insert(
            """
            INSERT INTO agent_states (user_id, session_id, state_key, payload, updated_at)
            VALUES (#{userId}, #{sessionId}, #{stateKey}, #{payload}::jsonb, now())
            ON CONFLICT (user_id, session_id, state_key)
            DO UPDATE SET payload = EXCLUDED.payload, updated_at = now()
            """)
    int upsert(
            @Param("userId") String userId,
            @Param("sessionId") UUID sessionId,
            @Param("stateKey") String stateKey,
            @Param("payload") String payload);

    /** 按槽位取 payload 文本；不存在返回 null。 */
    @Select(
            """
            SELECT payload::text FROM agent_states
            WHERE user_id = #{userId} AND session_id = #{sessionId} AND state_key = #{stateKey}
            """)
    String findPayload(
            @Param("userId") String userId,
            @Param("sessionId") UUID sessionId,
            @Param("stateKey") String stateKey);
}
