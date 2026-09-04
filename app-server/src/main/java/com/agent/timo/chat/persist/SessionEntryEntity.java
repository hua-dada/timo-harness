package com.agent.timo.chat.persist;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * {@code session_entries} 行：一条前端历史消息（append-only，不更新不删除）。
 *
 * <p>{@code kind} = 消息 role（user/assistant/toolResult/compactionSummary）；
 * {@code payload} = 该消息按前端 {@code messages-history.ts} 契约序列化的 JSON（存取零转换）。
 * {@code position}（全局单调标识列）不在实体里——由 PG IDENTITY 自动填，仅用于回放排序。
 */
@TableName("session_entries")
public class SessionEntryEntity {

    @TableId(value = "id", type = IdType.INPUT)
    private UUID id;

    private UUID sessionId;

    /** 链式父节点：M1-11 为线性链，M2-7 fork 从某 entry 分叉。 */
    private UUID parentId;

    private String kind;

    /** JSONB 文本（直写依赖数据源 URL 的 stringtype=unspecified）。 */
    private String payload;

    private OffsetDateTime createdAt;

    public SessionEntryEntity() {
        // MyBatis-Plus 反射构造
    }

    public SessionEntryEntity(UUID id, UUID sessionId, UUID parentId, String kind, String payload) {
        this.id = id;
        this.sessionId = sessionId;
        this.parentId = parentId;
        this.kind = kind;
        this.payload = payload;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getSessionId() {
        return sessionId;
    }

    public void setSessionId(UUID sessionId) {
        this.sessionId = sessionId;
    }

    public UUID getParentId() {
        return parentId;
    }

    public void setParentId(UUID parentId) {
        this.parentId = parentId;
    }

    public String getKind() {
        return kind;
    }

    public void setKind(String kind) {
        this.kind = kind;
    }

    public String getPayload() {
        return payload;
    }

    public void setPayload(String payload) {
        this.payload = payload;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
