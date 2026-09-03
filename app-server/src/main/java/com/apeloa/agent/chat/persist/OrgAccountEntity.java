package com.apeloa.agent.chat.persist;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;

/**
 * {@code org_accounts} 行（M1-3 密码登录）。主键 username 即全库 userId（sessions.user_id 等 FK
 * 都指向它）。无 tenant 列——本仓的「多租户」就是 user 级隔离（见 03-data-api §3.1 偏差注记）。
 */
@TableName("org_accounts")
public class OrgAccountEntity {

    @TableId(value = "username", type = IdType.INPUT)
    private String username;

    private String realName;

    private String mobile;

    /** bcrypt($2a$, cost 10)，与源项目 bcryptjs 互认；M1-3 迁移 V3 预置 admin/dev。 */
    private String passwordHash;

    private boolean platformAdmin;

    private OffsetDateTime createdAt;

    private OffsetDateTime updatedAt;

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getRealName() {
        return realName;
    }

    public void setRealName(String realName) {
        this.realName = realName;
    }

    public String getMobile() {
        return mobile;
    }

    public void setMobile(String mobile) {
        this.mobile = mobile;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public boolean isPlatformAdmin() {
        return platformAdmin;
    }

    public void setPlatformAdmin(boolean platformAdmin) {
        this.platformAdmin = platformAdmin;
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
