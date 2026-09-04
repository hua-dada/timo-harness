package com.agent.timo.workspace;

import com.agent.timo.core.bash.CommandExecutor;
import com.agent.timo.core.bash.ProcessRunner;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link CommandExecutor} 的 Docker 实现（M1-9）：命令在共享容器内以该用户 uid 执行，
 * cwd/HOME/TMPDIR 锁定其 /data 子目录——BashTool 的沙箱后端。
 *
 * <p>argv 构造复用 {@link DockerArgs#buildDockerExecArgs}（uid 隔离语义与源项目一致）；
 * 超时/输出治理复用 {@code ProcessRunner}。文件类工具（read/write/edit）不经容器，
 * 由 Java 进程直接读写 per-user 目录（同源项目 HTTP 文件路由的权限模型，见 M1-12）。
 */
public class DockerExecExecutor implements CommandExecutor {

    private final SandboxPaths paths;
    private final String container;
    private final long uid;
    private final String home;
    private final String tmpdir;

    public DockerExecExecutor(SandboxPaths paths, String container, long uid,
            String home, String tmpdir) {
        this.paths = paths;
        this.container = container;
        this.uid = uid;
        this.home = home;
        this.tmpdir = tmpdir;
    }

    /** 从句柄构造（最常用路径）。 */
    public static DockerExecExecutor of(SandboxManager.SandboxHandle handle, SandboxPaths paths) {
        if (handle.linuxUid() == null || handle.containerName() == null) {
            throw new IllegalArgumentException("local 模式句柄无 uid/容器，不能构造 Docker 执行器");
        }
        return new DockerExecExecutor(paths, handle.containerName(), handle.linuxUid(),
                handle.containerHome(), handle.containerTmpdir());
    }

    @Override
    public Outcome execute(Path workdir, String command, Duration timeout) {
        List<String> argv = buildArgv(paths, workdir, container, uid, home, tmpdir, command);
        ProcessBuilder pb = new ProcessBuilder(argv).directory(paths.sandboxRoot().toFile());
        pb.environment().put("LANG", "C.UTF-8");
        ProcessRunner.Outcome out = ProcessRunner.run(pb, timeout);
        return new Outcome(out.exitCode(), out.stdout(), out.stderr(), out.timedOut());
    }

    /** 完整 argv（docker exec … bash -c command）。workdir 为宿主路径，映射到容器内 cwd。 */
    static List<String> buildArgv(SandboxPaths paths, Path workdir, String container, long uid,
            String home, String tmpdir, String command) {
        DockerArgs.ExecSpec spec = new DockerArgs.ExecSpec(
                container,
                uid,
                paths.toContainerPath(workdir),
                home,
                tmpdir,
                null,
                null,
                List.of("bash", "-c", command));
        List<String> args = DockerArgs.buildDockerExecArgs(spec);
        List<String> argv = new ArrayList<>(args.size() + 1);
        argv.add("docker");
        argv.addAll(args);
        return argv;
    }
}
