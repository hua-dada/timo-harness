package com.apeloa.agent.workspace;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 纯函数构造 docker run / docker exec 参数（移植自源项目 sandbox/docker.ts，
 * docker.test.ts 断言见 DockerArgsTest）。
 *
 * <p>与源项目的差异：Java 版容器只是 bash/文件沙箱（模型调用在 JVM 进程内），
 * 不再注入 NEWAPI_* 与 pi 相关 env；保留通用 env 注入口供插件等场景，挂载与限额语义不变。
 */
public final class DockerArgs {

    private DockerArgs() {
    }

    /** run -d 入参：挂载 + 资源限额 + env。 */
    public record RunSpec(
            String containerName,
            /** 宿主 sandboxRoot，挂载到容器 /data。 */
            String sandboxRoot,
            /** 沙箱镜像（tini PID 1 + bash）。 */
            String image,
            String memory,
            String cpus,
            String pidsLimit,
            /** 额外 env（可空）。 */
            Map<String, String> env,
            /** 插件 registry 根目录（可空，只读挂到 /opt/plugins）。 */
            String pluginsRootDir) {

        public RunSpec {
            env = env == null ? Map.of() : new LinkedHashMap<>(env);
        }

        /** 模板式构造：容器名与 sandboxRoot 由 SandboxManager 填，其余可预配。 */
        public static Builder builder() {
            return new Builder();
        }

        public static final class Builder {
            private String image;
            private String memory;
            private String cpus;
            private String pidsLimit;
            private Map<String, String> env = Map.of();
            private String pluginsRootDir;

            public Builder image(String v) {
                this.image = v;
                return this;
            }

            public Builder memory(String v) {
                this.memory = v;
                return this;
            }

            public Builder cpus(String v) {
                this.cpus = v;
                return this;
            }

            public Builder pidsLimit(String v) {
                this.pidsLimit = v;
                return this;
            }

            public Builder env(Map<String, String> v) {
                this.env = v;
                return this;
            }

            public Builder pluginsRootDir(String v) {
                this.pluginsRootDir = v;
                return this;
            }

            public RunSpec build(String containerName, String sandboxRoot) {
                return new RunSpec(containerName, sandboxRoot, image, memory, cpus, pidsLimit, env,
                        pluginsRootDir);
            }
        }
    }

    /** 默认资源限额（所有用户共用的共享容器总上限）。 */
    public static final String DEFAULT_MEMORY = "4g";
    public static final String DEFAULT_CPUS = "4.0";
    public static final String DEFAULT_PIDS = "4096";
    public static final String DEFAULT_IMAGE = "pi-sandbox:dev";

    /** 构造共享容器 docker run -d 参数：限额 + no-new-privileges + sandboxRoot→/data。 */
    public static List<String> buildDockerRunArgs(RunSpec spec) {
        List<String> args = new ArrayList<>(List.of(
                "run", "-d",
                "--name", spec.containerName(),
                "--memory", spec.memory() == null ? DEFAULT_MEMORY : spec.memory(),
                "--cpus", spec.cpus() == null ? DEFAULT_CPUS : spec.cpus(),
                "--pids-limit", spec.pidsLimit() == null ? DEFAULT_PIDS : spec.pidsLimit(),
                // no-new-privileges 容器级生效（docker exec 不支持此 flag，只能 run 时设），exec 继承。
                "--security-opt", "no-new-privileges=true",
                "--restart", "no"));
        spec.env().forEach((k, v) -> args.addAll(List.of("-e", k + "=" + v)));
        // 整个 sandboxRoot 挂到 /data（所有用户子目录的根）；bash 的 cwd/HOME/TMPDIR 都在 /data 下。
        args.addAll(List.of("-v", toPosix(spec.sandboxRoot()) + ":" + SandboxPaths.DATA_MOUNT));
        if (spec.pluginsRootDir() != null && !spec.pluginsRootDir().isBlank()) {
            args.addAll(List.of("-v", toPosix(spec.pluginsRootDir()) + ":/opt/plugins:ro"));
        }
        args.addAll(List.of("-w", SandboxPaths.DATA_MOUNT));
        args.add(spec.image() == null ? DEFAULT_IMAGE : spec.image());
        return args;
    }

    /** docker exec 入参（per-user：以该 uid 跑命令，cwd/HOME/TMPDIR 锁定其 /data 子目录）。 */
    public record ExecSpec(
            String container,
            /** 运行 uid（= sandboxes.linux_uid）。gid 取同值（数值私有组，无 /etc/group 项）。 */
            long uid,
            /** 容器内工作目录（/data/\<userId\>/workspace）。 */
            String workspaceCwd,
            /** 容器内 HOME。 */
            String home,
            /** 容器内 TMPDIR（/data/tmp-by-uid/\<uid\> 短路径）。 */
            String tmpdir,
            /** 动态 env（可空，须显式传入：docker 子进程的宿主 env 不自动注入容器）。 */
            Map<String, String> dynamicEnv,
            /** prlimit 限额（可空不注入）。 */
            Prlimit prlimit,
            /** 容器内执行的命令及参数（如 [bash, -c, cmd]）。 */
            List<String> command) {

        public ExecSpec {
            dynamicEnv = dynamicEnv == null ? Map.of() : new LinkedHashMap<>(dynamicEnv);
            command = List.copyOf(command);
        }

        public long gid() {
            return uid;
        }
    }

    /** per-进程资源限额（经 prlimit 注入 RLIMIT_*）。任一字段缺省则该项不限。 */
    public record Prlimit(Integer nproc, Long asBytes, Integer cpuSec) {
    }

    /** 构造 docker exec 参数（buildDockerExecArgs 语义移植）：
     *  exec -i --user uid:gid -w cwd -e HOME -e TMPDIR [-e 动态env] 容器 [prlimit … --] 命令。 */
    public static List<String> buildDockerExecArgs(ExecSpec spec) {
        List<String> args = new ArrayList<>(List.of(
                "exec", "-i",
                "--user", spec.uid() + ":" + spec.gid(),
                "-w", spec.workspaceCwd(),
                "-e", "HOME=" + spec.home(),
                "-e", "TMPDIR=" + spec.tmpdir()));
        spec.dynamicEnv().forEach((k, v) -> args.addAll(List.of("-e", k + "=" + v)));
        args.add(spec.container());
        List<String> prlimit = buildPrlimitPrefix(spec.prlimit());
        if (prlimit != null) {
            args.addAll(prlimit);
            args.add("--");
        }
        args.addAll(spec.command());
        return args;
    }

    /** prlimit 前缀（不含末尾 --）；无任何限额返回 null（不注入）。 */
    static List<String> buildPrlimitPrefix(Prlimit p) {
        if (p == null) {
            return null;
        }
        List<String> args = new ArrayList<>();
        args.add("prlimit");
        if (p.nproc() != null) {
            args.add("--nproc=" + p.nproc());
        }
        if (p.asBytes() != null) {
            args.add("--as=" + p.asBytes());
        }
        if (p.cpuSec() != null) {
            args.add("--cpu=" + p.cpuSec());
        }
        return args.size() > 1 ? args : null;
    }

    private static String toPosix(String p) {
        return p.replace("\\", "/");
    }
}
