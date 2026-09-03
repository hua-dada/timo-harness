package com.apeloa.agent.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DockerExecExecutorTest {

    @TempDir
    Path tmp;

    @Test
    void argv完整_挂载映射与uid隔离() {
        SandboxPaths paths = new SandboxPaths(tmp.resolve("sb"));
        Path workdir = paths.userWorkspaceDir("u1");
        List<String> argv = DockerExecExecutor.buildArgv(paths, workdir, "pi-shared-java",
                100000L, "/data/u1/home", "/data/tmp-by-uid/100000", "echo hi");
        assertThat(argv).containsExactly(
                "docker", "exec", "-i",
                "--user", "100000:100000",
                "-w", "/data/u1/workspace",
                "-e", "HOME=/data/u1/home",
                "-e", "TMPDIR=/data/tmp-by-uid/100000",
                "pi-shared-java",
                "bash", "-c", "echo hi");
    }

    @Test
    void workdir嵌套子目录也映射() {
        SandboxPaths paths = new SandboxPaths(tmp.resolve("sb"));
        List<String> argv = DockerExecExecutor.buildArgv(paths,
                paths.userWorkspaceDir("u1").resolve("sub").resolve("dir"),
                "pi-shared-java", 100001L, "/data/u1/home", "/data/tmp-by-uid/100001", "ls");
        assertThat(argv).containsSubsequence("-w", "/data/u1/workspace/sub/dir");
    }

    @Test
    void of拒绝local句柄() {
        SandboxPaths paths = new SandboxPaths(tmp.resolve("sb"));
        SandboxManager m = new SandboxManager(paths, args -> new DockerCli.Result(0, "", ""),
                new InMemorySandboxStore(), false);
        SandboxManager.SandboxHandle h = m.acquire("u1");
        assertThatThrownBy(() -> DockerExecExecutor.of(h, paths))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("local 模式");
    }

    @Test
    void of接受docker句柄() {
        SandboxPaths paths = new SandboxPaths(tmp.resolve("sb"));
        RecordingDockerCli docker = new RecordingDockerCli();
        SandboxManager m = new SandboxManager(paths, docker, new InMemorySandboxStore(), true);
        SandboxManager.SandboxHandle h = m.acquire("u1");
        DockerExecExecutor executor = DockerExecExecutor.of(h, paths);
        assertThat(executor).isNotNull();
    }
}
