package com.apeloa.agent.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SandboxManagerTest {

    @TempDir
    Path tmp;

    private SandboxPaths paths;
    private RecordingDockerCli docker;
    private InMemorySandboxStore store;

    @BeforeEach
    void setUp() {
        paths = new SandboxPaths(tmp.resolve("sb"));
        docker = new RecordingDockerCli();
        store = new InMemorySandboxStore();
    }

    private SandboxManager dockerManager() {
        return new SandboxManager(paths, docker, store, true);
    }

    @Test
    void 首次acquire重建共享容器并收口目录() {
        SandboxManager m = dockerManager();
        SandboxManager.SandboxHandle h = m.acquire("u1");
        assertThat(h.containerName()).isEqualTo("pi-shared-java");
        assertThat(h.linuxUid()).isEqualTo(100000L);
        assertThat(h.containerWorkspaceCwd()).isEqualTo("/data/u1/workspace");
        assertThat(h.containerHome()).isEqualTo("/data/u1/home");
        assertThat(h.containerTmpdir()).isEqualTo("/data/tmp-by-uid/100000");
        assertThat(Files.isDirectory(paths.userWorkspaceDir("u1"))).isTrue();
        assertThat(Files.isDirectory(paths.userHomeDir("u1"))).isTrue();
        assertThat(Files.isDirectory(paths.tmpByUidDir(100000L))).isTrue();
        assertThat(docker.count("run ")).isEqualTo(1);
        String runCall = docker.calls.stream().filter(c -> c.startsWith("run ")).findFirst().orElseThrow();
        assertThat(runCall).contains("--name pi-shared-java");
        assertThat(runCall).contains("--security-opt no-new-privileges=true");
        assertThat(runCall).contains(
                "-v " + paths.sandboxRoot().toString().replace('\\', '/') + ":/data");
        assertThat(store.find("u1")).hasValueSatisfying(row -> {
            assertThat(row.containerName()).isEqualTo("pi-shared-java");
            assertThat(row.linuxUid()).isEqualTo(100000L);
        });
    }

    @Test
    void 容器running时acquire不重复run() {
        docker.status = "running";
        dockerManager().acquire("u1");
        assertThat(docker.count("run ")).isZero();
        assertThat(docker.count("inspect ")).isEqualTo(1);
    }

    @Test
    void paused自愈unpause() {
        docker.status = "paused";
        dockerManager().acquire("u1");
        assertThat(docker.calls).contains("unpause pi-shared-java");
        assertThat(docker.count("run ")).isZero();
    }

    @Test
    void exited自愈start() {
        docker.status = "exited";
        dockerManager().acquire("u1");
        assertThat(docker.calls).contains("start pi-shared-java");
        assertThat(docker.count("run ")).isZero();
    }

    @Test
    void 每用户独立uid_同一共享容器() {
        SandboxManager m = dockerManager();
        assertThat(m.acquire("u1").linuxUid()).isEqualTo(100000L);
        assertThat(m.acquire("u2").linuxUid()).isEqualTo(100001L);
        assertThat(docker.count("run ")).isEqualTo(1);
    }

    @Test
    void 重复acquire同用户uid稳定() {
        SandboxManager m = dockerManager();
        m.acquire("u1");
        m.release();
        assertThat(m.acquire("u1").linuxUid()).isEqualTo(100000L);
        assertThat(docker.count("run ")).isEqualTo(1);
    }

    @Test
    void refCount保护降级操作() {
        SandboxManager m = dockerManager();
        m.acquire("u1");
        m.acquire("u2");
        assertThat(m.globalRefCount()).isEqualTo(2);
        assertThatThrownBy(m::pause).isInstanceOf(IllegalStateException.class).hasMessageContaining("refCount");
        assertThatThrownBy(m::stop).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(m::destroy).isInstanceOf(IllegalStateException.class);
        assertThat(docker.count("pause ")).isZero();
        assertThat(docker.count("stop ")).isZero();
        assertThat(docker.count("rm ")).isZero();

        m.release();
        assertThatThrownBy(m::destroy).isInstanceOf(IllegalStateException.class);
        m.release();
        assertThat(m.globalRefCount()).isZero();

        m.destroy();
        assertThat(docker.calls).contains("rm -f pi-shared-java");
    }

    @Test
    void 并发acquire只run一次() throws Exception {
        int n = 4;
        SandboxManager m = dockerManager();
        CyclicBarrier barrier = new CyclicBarrier(n);
        List<Future<SandboxManager.SandboxHandle>> futures = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(n)) {
            for (int i = 0; i < n; i++) {
                String userId = "u" + i;
                futures.add(pool.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return m.acquire(userId);
                }));
            }
            for (Future<SandboxManager.SandboxHandle> f : futures) {
                assertThat(f.get(30, TimeUnit.SECONDS)).isNotNull();
            }
        }
        assertThat(docker.count("run ")).isEqualTo(1);
        assertThat(m.globalRefCount()).isEqualTo(n);
    }

    @Test
    void local模式只建目录不碰docker() {
        SandboxManager m = new SandboxManager(paths, docker, store, false);
        SandboxManager.SandboxHandle h = m.acquire("u1");
        assertThat(h.containerName()).isNull();
        assertThat(h.linuxUid()).isNull();
        assertThat(h.containerTmpdir()).isNull();
        assertThat(h.containerWorkspaceCwd()).isEqualTo("/data/u1/workspace");
        assertThat(Files.isDirectory(paths.userWorkspaceDir("u1"))).isTrue();
        assertThat(docker.calls).isEmpty();
        assertThat(store.find("u1")).hasValueSatisfying(
                row -> assertThat(row.containerName()).isNull());
        assertThatThrownBy(m::pause).isInstanceOf(IllegalStateException.class).hasMessageContaining("local");
        assertThatThrownBy(m::destroy).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void release钳到零不为负() {
        SandboxManager m = dockerManager();
        m.release();
        m.release();
        assertThat(m.globalRefCount()).isZero();
    }

    @Test
    void acquire后空闲超时可被destroy() {
        // reclaim 前置语义：refCount=0 且容器存在 → destroy 可达
        docker.status = "running";
        store.upsert("u1", "pi-shared-java");
        dockerManager().destroy();
        assertThat(docker.calls).contains("rm -f pi-shared-java");
    }

    @Test
    void docker模式run失败抛DockerError() {
        DockerCli failing = args -> new DockerCli.Result(125, "", "docker: invalid option");
        SandboxManager m = new SandboxManager(paths, failing, store, true);
        assertThatThrownBy(() -> m.acquire("u1"))
                .isInstanceOf(DockerCli.DockerError.class)
                .hasMessageContaining("code=125");
    }
}
