package com.apeloa.agent.chat.persist;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * {@code sessions} 行。id 由应用侧生成（UUID），故 {@code IdType.INPUT}；
 * {@code persisted_msgs}/{@code last_entry_id} 是 entries 增量落盘游标（见 V2 迁移注释）。
 */
@TableName("sessions")
public class SessionEntity {

    @TableId(value = "id", type = IdType.INPUT)
    private UUID id;

    /** 归属用户（FK org_accounts.username）。 */
    private String userId;

    private String title;

    /** 已从 AgentState.context 投影进 session_entries 的 Msg 条数（增量游标）。 */
    private int persistedMsgs;

    /** entries 链尾，下批首行的 parent_id。 */
    private UUID lastEntryId;

    private OffsetDateTime createdAt;

    private OffsetDateTime updatedAt;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public int getPersistedMsgs() {
        return persistedMsgs;
    }

    public void setPersistedMsgs(int persistedMsgs) {
        this.persistedMsgs = persistedMsgs;
    }

    public UUID getLastEntryId() {
        return lastEntryId;
    }

    public void setLastEntryId(UUID lastEntryId) {
        this.lastEntryId = lastEntryId;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(OffsetDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
