package com.agent.timo.workspace;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * sandboxes 表访问 SPI（无副作用，不操作容器）。移植自源项目 sandbox/store.ts；
 * 生产实现（JPA/Jooq + {@code pi_linux_uid_seq} 序列）随 M1-11 落在 app-server，
 * 内存实现供单测与 local 开发模式。
 */
public interface SandboxStore {

    /** sandboxes 行。spec §3.1：user_id PK + linux_uid UNIQUE（无 status 列——容器实况以 inspect 为真源）。 */
    record SandboxRow(String userId, String containerName, Long linuxUid) {
    }

    /** upsert per-user 行（并发安全）：不存在则插入并分配 linuxUid（docker 模式）；local 模式 containerName=null、uid=null。 */
    SandboxRow upsert(String userId, String containerName);

    Optional<SandboxRow> find(String userId);

    /** 刷 lastActiveAt（活动心跳，供 ReclaimScheduler 判全局空闲；失败静默）。 */
    void touch(String userId);

    /** 所有行里最近一次活跃时间（共享容器回收用：聚合全局活跃度）。 */
    Optional<Instant> mostRecentActivity();

    /** 全部 sandbox 行（可回收判定用）。 */
    List<SandboxRow> findAll();
}
