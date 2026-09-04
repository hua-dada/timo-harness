package com.agent.timo.workspace;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 共享容器生命周期编排（所有用户复用同一容器）。移植自源项目 sandbox/manager.ts。
 *
 * <p>核心解耦：容器常驻（docker run -d），命令经 {@link DockerExecExecutor} 以 per-user uid
 * 执行。文件隔离：每用户独立 Linux uid + 目录 0700（acquire 时收口），非 root 进程受 DAC 约束。
 *
 * <p>全局 refCount：所有活跃会话 + 运行中任务共享一个计数器，pause/stop/destroy 在 >0 时拒绝
 * （保护共享容器——误杀正在跑的他人）。自愈：acquire 时 inspect，缺失→重建，状态漂移→修正。
 *
 * <p>与源项目的差异（对齐 spec）：sandboxes 表无 status 列，容器实况以 docker inspect 为真源，
 * 内存不重复维护状态机。
 */
public class SandboxManager {

    /** 共享容器名（tech-design 2.1：pi-shared-java；所有用户复用，隔离靠 uid + 0700）。 */
    public static final String SHARED_CONTAINER_NAME = "pi-shared-java";

    /** acquire 得到的沙箱句柄。 */
    public record SandboxHandle(
            String userId,
            String containerName,
            Long linuxUid,
            java.nio.file.Path workspaceDir,
            java.nio.file.Path homeDir,
            java.nio.file.Path tmpByUidDir,
            String containerWorkspaceCwd,
            String containerHome,
            String containerTmpdir) {
    }

    private final SandboxPaths paths;
    private final DockerCli docker;
    private final SandboxStore store;
    /** local 模式（无 docker）只建目录 + DB 行。 */
    private final boolean dockerMode;
    /** run -d 的镜像与限额（docker 模式用）。 */
    private final DockerArgs.RunSpec.Builder runSpecTemplate;

    /** 全局引用计数：pause/stop/destroy 在 >0 时拒绝。 */
    private final AtomicInteger globalRefs = new AtomicInteger();
    /** ensureRunning 去重：共享容器并发 acquire 串行化，避免双 docker run 撞名。 */
    private final Map<String, CompletableFuture<Void>> inflight = new ConcurrentHashMap<>();

    public SandboxManager(SandboxPaths paths, DockerCli docker, SandboxStore store, boolean dockerMode) {
        this(paths, docker, store, dockerMode, null);
    }

    public SandboxManager(SandboxPaths paths, DockerCli docker, SandboxStore store, boolean dockerMode,
            DockerArgs.RunSpec.Builder runSpecTemplate) {
        this.paths = paths;
        this.docker = docker;
        this.store = store;
        this.dockerMode = dockerMode;
        this.runSpecTemplate = runSpecTemplate;
    }

    /** 取得（或创建/恢复）某用户的沙箱句柄；共享容器所有用户复用。 */
    public SandboxHandle acquire(String userId) {
        try {
            SandboxPaths.ensureDir(paths.userWorkspaceDir(userId));
            SandboxPaths.ensureDir(paths.userHomeDir(userId));

            String containerName = dockerMode ? SHARED_CONTAINER_NAME : null;
            SandboxStore.SandboxRow row = store.upsert(userId, containerName);
            Long uid = row.linuxUid();
            if (dockerMode) {
                if (uid == null) {
                    throw new IllegalStateException("docker 模式 sandbox 行缺少 linuxUid（store 异常）");
                }
                ensureRunning(containerName);
                // 文件隔离：目录由 Java 进程（root）创建，收口 chown uid + 0700，
                // 容器内非 root 的 bash 才能读写自己目录、被 0700 挡住读他人。
                SecureDirs.secureUserDir(paths.userRootDir(userId), uid);
                // per-uid 短 TMPDIR（21 字符，避开 chromium sun_path[108] 越界）。
                SandboxPaths.ensureDir(paths.tmpByUidDir(uid));
                SecureDirs.secureTmpByUidDir(paths.tmpByUidDir(uid), uid);
            }

            globalRefs.incrementAndGet();
            store.touch(userId);
            return new SandboxHandle(
                    userId, containerName, uid,
                    paths.userWorkspaceDir(userId), paths.userHomeDir(userId),
                    uid == null ? null : paths.tmpByUidDir(uid),
                    paths.containerUserDir(userId, "workspace"),
                    paths.containerUserDir(userId, "home"),
                    uid == null ? null : paths.containerTmpByUidDir(uid));
        } catch (IOException e) {
            throw new UncheckedIOException("沙箱目录创建失败: " + userId, e);
        }
    }

    /** 会话/任务断开时调：全局 refCount--（不销毁共享容器）。 */
    public void release() {
        globalRefs.updateAndGet(n -> Math.max(0, n - 1));
    }

    /** 共享容器当前活跃引用数（reclaim 据此判断能否降级）。 */
    public int globalRefCount() {
        return globalRefs.get();
    }

    /** docker pause。refCount>0 拒绝。 */
    public void pause() {
        assertDockerMode("pause");
        assertNotInUse("pause");
        docker.pause(SHARED_CONTAINER_NAME);
    }

    /** docker unpause。 */
    public void resume() {
        if (!dockerMode) {
            return;
        }
        docker.unpause(SHARED_CONTAINER_NAME);
    }

    /** docker stop 共享容器（保留实体）。refCount>0 拒绝。 */
    public void stop() {
        assertDockerMode("stop");
        assertNotInUse("stop");
        docker.stop(SHARED_CONTAINER_NAME);
    }

    /** docker rm -f 销毁共享容器。refCount>0 拒绝。下次 acquire 触发重建。 */
    public void destroy() {
        assertDockerMode("destroy");
        assertNotInUse("destroy");
        docker.remove(SHARED_CONTAINER_NAME);
    }

    // —— 内部 ——

    /** 让共享容器进入 RUNNING（inflight 去重，串行化所有用户的并发 acquire）。 */
    private void ensureRunning(String containerName) {
        try {
            inflight.compute(containerName, (name, existing) -> {
                if (existing != null) {
                    return existing;
                }
                CompletableFuture<Void> p = CompletableFuture.runAsync(() -> doEnsureRunning(containerName));
                p.whenComplete((v, e) -> inflight.remove(containerName, p));
                return p;
            }).join();
        } catch (java.util.concurrent.CompletionException e) {
            // join() 把异步异常包一层 CompletionException，还原为原始类型（如 DockerError）
            if (e.getCause() instanceof RuntimeException re) {
                throw re;
            }
            if (e.getCause() instanceof Error err) {
                throw err;
            }
            throw e;
        }
    }

    private void doEnsureRunning(String containerName) {
        String status = docker.inspectStatus(containerName);
        if (status == null) {
            // 共享容器缺失（从未创建 / 被外部 rm）→ 重建（复用同名，挂整个 sandboxRoot→/data）。
            DockerArgs.RunSpec spec = runSpecTemplate != null
                    ? runSpecTemplate.build(containerName, paths.sandboxRoot().toString())
                    : new DockerArgs.RunSpec(containerName, paths.sandboxRoot().toString(),
                            null, null, null, null, null, null);
            docker.checked("run", DockerArgs.buildDockerRunArgs(spec));
            return;
        }
        // 状态漂移自愈：paused → unpause；exited/created → start。
        if ("paused".equals(status)) {
            docker.unpause(containerName);
        } else if (!"running".equals(status)) {
            docker.start(containerName);
        }
    }

    private void assertDockerMode(String action) {
        if (!dockerMode) {
            throw new IllegalStateException("local 模式不支持 " + action);
        }
    }

    private void assertNotInUse(String action) {
        if (globalRefs.get() > 0) {
            throw new IllegalStateException(
                    "共享容器正被使用（refCount=" + globalRefs.get() + "），拒绝 " + action);
        }
    }
}
