package com.agent.timo.chat.persist;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * {@code agent_states} 行：一个 (userId, sessionId, key) 槽位的框架状态 JSON 文档。
 *
 * <p>{@code payload} 列为 JSONB，实体侧就是序列化好的 JSON 文本——依赖数据源 URL 带
 * {@code stringtype=unspecified}（见 application.yml），PG 才接受 setString 直写 jsonb；
 * 读取侧 {@code rs.getString} 对 jsonb 天然返回文本，无需 TypeHandler。
 */
@TableName("agent_states")
public class AgentStateEntity {

    /** 复合主键的第一段；MyBatis-Plus 无复合主键支持，按三列查询而非 selectById。 */
    @TableId(value = "user_id", type = IdType.INPUT)
    private String userId;

    private UUID sessionId;

    private String stateKey;

    private String payload;

    private OffsetDateTime updatedAt;

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public UUID getSessionId() {
        return sessionId;
    }

    public void setSessionId(UUID sessionId) {
        this.sessionId = sessionId;
    }

    public String getStateKey() {
        return stateKey;
    }

    public void setStateKey(String stateKey) {
        this.stateKey = stateKey;
    }

    public String getPayload() {
        return payload;
    }

    public void setPayload(String payload) {
        this.payload = payload;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(OffsetDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
