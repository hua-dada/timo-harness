package com.apeloa.agent.workspace;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 空闲共享容器定时回收（M1-10，移植自源项目 sandbox/reclaim.ts）：所有用户复用
 * {@code pi-shared-java}，全局空闲超阈值才 destroy。
 *
 * <p>关键：refCount&gt;0（任一用户活跃）绝不降级，否则误杀正在跑的他人（destroy 内部
 * assertNotInUse 再兜底）。共享容器 ENTRYPOINT 常驻，空闲开销极小，只保留 destroy 档
 * （默认 7d）；容器被 destroy 后下次 acquire 的 ensureRunning 重建（目录与 uid 不变）。
 * 单实例：内存 refCount 配合；进程崩溃后 refCount 归零，回收器扫全局空闲超时正常 destroy。
 */
public final class ReclaimScheduler {

    private static final Logger log = LoggerFactory.getLogger(ReclaimScheduler.class);

    /** 默认巡检间隔（源项目 SANDBOX_RECLAIM_INTERVAL_SEC=60）。 */
    public static final long DEFAULT_INTERVAL_MS = 60_000;

    /** 默认 destroy 空闲阈值（源项目 SANDBOX_DESTROY_MS=7d）。 */
    public static final long DEFAULT_DESTROY_MS = 7L * 24 * 60 * 60_000;

    private final SandboxManager manager;
    private final SandboxStore store;
    private final long intervalMs;
    private final long destroyMs;
    private final Clock clock;

    private ScheduledExecutorService timer;
    private boolean busy;

    public ReclaimScheduler(SandboxManager manager, SandboxStore store) {
        this(manager, store, DEFAULT_INTERVAL_MS, DEFAULT_DESTROY_MS, Clock.systemUTC());
    }

    public ReclaimScheduler(SandboxManager manager, SandboxStore store,
            long intervalMs, long destroyMs, Clock clock) {
        this.manager = manager;
        this.store = store;
        this.intervalMs = intervalMs;
        this.destroyMs = destroyMs;
        this.clock = clock;
    }

    /** 启动定时回收（幂等）：立即跑一轮（清理上次崩溃遗留的孤儿），之后按 interval 周期。 */
    public synchronized void start() {
        if (timer != null) {
            return;
        }
        timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "sandbox-reclaim");
            t.setDaemon(true);
            return t;
        });
        timer.execute(() -> runTickSafely("首次 tick 失败"));
        timer.scheduleWithFixedDelay(this::runTickSafely, intervalMs, intervalMs,
                TimeUnit.MILLISECONDS);
    }

    public synchronized void stop() {
        if (timer != null) {
            timer.shutdownNow();
            timer = null;
        }
    }

    // —— 内部 ——

    private void runTickSafely() {
        runTickSafely("tick 失败");
    }

    private void runTickSafely(String errLabel) {
        try {
            tick();
        } catch (RuntimeException e) {
            log.error("{}: {}", errLabel, e.toString());
        }
    }

    /** 单轮回收（包内可见供单测直调）。 */
    void tick() {
        if (busy) {
            return; // 上一轮未完成，跳过防堆积
        }
        busy = true;
        try {
            // 共享容器：任一用户活跃（refCount>0）即跳过，保护在跑的他人。
            if (manager.globalRefCount() > 0) {
                return;
            }
            Optional<Instant> last = store.mostRecentActivity();
            if (last.isEmpty()) {
                return;
            }
            long idle = clock.millis() - last.get().toEpochMilli();
            if (idle < destroyMs) {
                return;
            }
            // 取任一 docker 模式 sandbox 行作 target：destroy 操作的就是共享容器。
            Optional<SandboxStore.SandboxRow> target = store.findAll().stream()
                    .filter(r -> r.containerName() != null)
                    .findFirst();
            if (target.isEmpty()) {
                return;
            }
            try {
                manager.destroy();
                log.info("共享容器空闲 {}ms 超阈值 {}ms，已 destroy（下次 acquire 自动重建）",
                        idle, destroyMs);
            } catch (RuntimeException e) {
                // refCount 竞态转正 / local 模式 / 容器已不在 → 跳过，下轮再判。
                log.debug("本轮 destroy 跳过：{}", e.toString());
            }
        } finally {
            busy = false;
        }
    }
}
