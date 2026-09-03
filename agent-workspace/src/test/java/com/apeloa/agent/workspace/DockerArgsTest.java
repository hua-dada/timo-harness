package com.apeloa.agent.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 移植自源项目 sandbox/docker.test.ts（3 个 exec argv 断言）+ run argv 补充覆盖。 */
class DockerArgsTest {

    private DockerArgs.ExecSpec baseSpec() {
        return new DockerArgs.ExecSpec(
                "pi-shared", 100000,
                "/data/t1/u1/workspace", "/data/t1/u1/home", "/data/tmp-by-uid/100000",
                null, null, List.of("pi", "--mode", "rpc"));
    }

    @Test
    void 注入user与cwd与HOME与TMPDIR_动态env在容器名之前() {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("PI_MCP_CONFIG", "/data/t1/u1/sessions/.mcp/config.json");
        env.put("PI_PLUGIN_CONFIGS", "{\"demo\":{\"token\":\"value with spaces\"}}");
        DockerArgs.ExecSpec spec = new DockerArgs.ExecSpec(
                "pi-shared", 100000,
                "/data/t1/u1/workspace", "/data/t1/u1/home", "/data/tmp-by-uid/100000",
                env, null, List.of("pi", "--mode", "rpc"));
        assertThat(DockerArgs.buildDockerExecArgs(spec)).containsExactly(
                "exec", "-i",
                "--user", "100000:100000",
                "-w", "/data/t1/u1/workspace",
                "-e", "HOME=/data/t1/u1/home",
                "-e", "TMPDIR=/data/tmp-by-uid/100000",
                "-e", "PI_MCP_CONFIG=/data/t1/u1/sessions/.mcp/config.json",
                "-e", "PI_PLUGIN_CONFIGS={\"demo\":{\"token\":\"value with spaces\"}}",
                "pi-shared",
                "pi", "--mode", "rpc");
    }

    @Test
    void 无动态env不插减e_无prlimit不插前缀() {
        assertThat(DockerArgs.buildDockerExecArgs(baseSpec())).containsExactly(
                "exec", "-i",
                "--user", "100000:100000",
                "-w", "/data/t1/u1/workspace",
                "-e", "HOME=/data/t1/u1/home",
                "-e", "TMPDIR=/data/tmp-by-uid/100000",
                "pi-shared",
                "pi", "--mode", "rpc");
    }

    @Test
    void prlimit注入在容器名后_含末尾双减号分隔() {
        DockerArgs.ExecSpec spec = new DockerArgs.ExecSpec(
                "pi-shared", 100000,
                "/data/t1/u1/workspace", "/data/t1/u1/home", "/data/tmp-by-uid/100000",
                null, new DockerArgs.Prlimit(512, null, 3600), List.of("pi", "--mode", "rpc"));
        assertThat(DockerArgs.buildDockerExecArgs(spec)).containsExactly(
                "exec", "-i",
                "--user", "100000:100000",
                "-w", "/data/t1/u1/workspace",
                "-e", "HOME=/data/t1/u1/home",
                "-e", "TMPDIR=/data/tmp-by-uid/100000",
                "pi-shared",
                "prlimit", "--nproc=512", "--cpu=3600", "--",
                "pi", "--mode", "rpc");
    }

    @Test
    void prlimit含内存上限() {
        DockerArgs.ExecSpec spec = new DockerArgs.ExecSpec(
                "pi-shared", 100000,
                "/data/t1/u1/workspace", "/data/t1/u1/home", "/data/tmp-by-uid/100000",
                null, new DockerArgs.Prlimit(null, 2L * 1024 * 1024 * 1024, null), List.of("bash", "-c", "ls"));
        assertThat(DockerArgs.buildDockerExecArgs(spec)).containsSubsequence(
                "pi-shared", "prlimit", "--as=" + (2L * 1024 * 1024 * 1024), "--", "bash", "-c", "ls");
    }

    @Test
    void run参数完整argv_默认限额与挂载() {
        DockerArgs.RunSpec spec = new DockerArgs.RunSpec(
                "pi-shared-java", "E:/sb", null, null, null, null, null, null);
        assertThat(DockerArgs.buildDockerRunArgs(spec)).containsExactly(
                "run", "-d",
                "--name", "pi-shared-java",
                "--memory", "4g",
                "--cpus", "4.0",
                "--pids-limit", "4096",
                "--security-opt", "no-new-privileges=true",
                "--restart", "no",
                "-v", "E:/sb:/data",
                "-w", "/data",
                "pi-sandbox:dev");
    }

    @Test
    void run带env与插件只读挂载() {
        DockerArgs.RunSpec spec = DockerArgs.RunSpec.builder()
                .image("pi-sandbox:custom")
                .memory("2g")
                .cpus("1.5")
                .pidsLimit("1024")
                .env(Map.of("FOO", "bar"))
                .pluginsRootDir("E:/plugins")
                .build("pi-shared-java", "E:/sb");
        assertThat(DockerArgs.buildDockerRunArgs(spec)).containsSubsequence(
                "--memory", "2g",
                "--cpus", "1.5",
                "--pids-limit", "1024",
                "-e", "FOO=bar",
                "-v", "E:/sb:/data",
                "-v", "E:/plugins:/opt/plugins:ro",
                "-w", "/data",
                "pi-sandbox:custom");
    }
}
