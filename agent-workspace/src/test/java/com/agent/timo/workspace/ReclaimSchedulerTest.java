package com.agent.timo.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReclaimSchedulerTest {

    @TempDir
    Path tmp;

    private SandboxPaths paths;
    private RecordingDockerCli docker;
    private InMemorySandboxStore store;
    private SandboxManager manager;
    private ReclaimScheduler scheduler;

    @BeforeEach
    void setUp() {
        paths = new SandboxPaths(tmp.resolve("sb"));
        docker = new RecordingDockerCli();
        store = new InMemorySandboxStore();
        manager = new SandboxManager(paths, docker, store, true);
        scheduler = new ReclaimScheduler(manager, store);
    }

    @Test
    void 活跃时不回收() {
        manager.acquire("u1");
        store.setLastActivity(Instant.now().minus(java.time.Duration.ofDays(30)));
        scheduler.tick();
        assertThat(docker.count("rm ")).isZero();
    }

    @Test
    void 空闲未超阈值不回收() {
        store.upsert("u1", "pi-shared-java");
        store.setLastActivity(Instant.now().minus(java.time.Duration.ofDays(1)));
        scheduler.tick();
        assertThat(docker.count("rm ")).isZero();
    }

    @Test
    void 空闲超7天destroy共享容器() {
        docker.status = "running";
        store.upsert("u1", "pi-shared-java");
        store.setLastActivity(Instant.now().minus(java.time.Duration.ofDays(8)));
        scheduler.tick();
        assertThat(docker.calls).contains("rm -f pi-shared-java");
    }

    @Test
    void 无活跃记录不回收() {
        store.upsert("u1", "pi-shared-java");
        // 未 setLastActivity → mostRecentActivity 为空
        scheduler.tick();
        assertThat(docker.count("rm ")).isZero();
    }

    @Test
    void 无docker模式sandbox行不回收() {
        store.upsert("u1", null);
        store.setLastActivity(Instant.now().minus(java.time.Duration.ofDays(30)));
        scheduler.tick();
        assertThat(docker.count("rm ")).isZero();
    }

    @Test
    void local模式tick静默() {
        SandboxManager local = new SandboxManager(paths, docker, store, false);
        ReclaimScheduler localScheduler = new ReclaimScheduler(local, store);
        store.upsert("u1", "pi-shared-java");
        store.setLastActivity(Instant.now().minus(java.time.Duration.ofDays(30)));
        assertThatCode(localScheduler::tick).doesNotThrowAnyException();
        assertThat(docker.count("rm ")).isZero();
    }

    @Test
    void startStop幂等且不抛() {
        assertThatCode(() -> {
            scheduler.start();
            scheduler.start();
            scheduler.stop();
            scheduler.stop();
        }).doesNotThrowAnyException();
    }

    @Test
    void 自定义阈值生效() {
        docker.status = "running";
        store.upsert("u1", "pi-shared-java");
        store.setLastActivity(Instant.now().minus(java.time.Duration.ofHours(2)));
        ReclaimScheduler aggressive = new ReclaimScheduler(
                manager, store, 60_000, java.time.Duration.ofHours(1).toMillis(),
                java.time.Clock.systemUTC());
        aggressive.tick();
        assertThat(docker.calls).contains("rm -f pi-shared-java");
    }
}
