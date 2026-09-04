package com.agent.timo.workspace;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * {@link SandboxStore} 内存实现：单测与 local 开发模式用。
 * uid 分配从 100000 起递增（对齐生产 {@code pi_linux_uid_seq} 语义）。
 */
public class InMemorySandboxStore implements SandboxStore {

    /** 生产序列起点（V1__init.sql：CREATE SEQUENCE pi_linux_uid_seq START WITH 100000）。 */
    public static final long UID_BASE = 100_000L;

    private final AtomicLong uidSeq = new AtomicLong(UID_BASE - 1);
    private final Map<String, SandboxRow> rows = new ConcurrentHashMap<>();
    private volatile Instant lastActivity;

    @Override
    public SandboxRow upsert(String userId, String containerName) {
        SandboxRow created = rows.compute(userId, (k, existing) -> {
            if (existing != null) {
                // 迁移兼容：旧行 containerName 漂移时刷新；docker 模式补分配 uid（双保险）。
                Long uid = existing.linuxUid() != null ? existing.linuxUid()
                        : (containerName != null ? uidSeq.incrementAndGet() : null);
                return new SandboxRow(k, containerName != null ? containerName : existing.containerName(), uid);
            }
            Long uid = containerName != null ? uidSeq.incrementAndGet() : null;
            return new SandboxRow(k, containerName, uid);
        });
        return created;
    }

    @Override
    public Optional<SandboxRow> find(String userId) {
        return Optional.ofNullable(rows.get(userId));
    }

    @Override
    public void touch(String userId) {
        lastActivity = Instant.now();
    }

    /** 测试用：固定活跃时间。 */
    public void setLastActivity(Instant at) {
        this.lastActivity = at;
    }

    @Override
    public Optional<Instant> mostRecentActivity() {
        return Optional.ofNullable(lastActivity);
    }

    @Override
    public List<SandboxRow> findAll() {
        return new ArrayList<>(rows.values());
    }
}
