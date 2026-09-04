package com.agent.timo.workspace;

import com.agent.timo.core.bash.ProcessRunner;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** {@link DockerCli} 的进程实现：spawn docker，30s 超时（控制面操作足够）。 */
public class ProcessDockerCli implements DockerCli {

    @Override
    public Result run(List<String> args) {
        List<String> argv = new ArrayList<>(args.size() + 1);
        argv.add("docker");
        argv.addAll(args);
        ProcessBuilder pb = new ProcessBuilder(argv);
        pb.environment().put("LANG", "C.UTF-8");
        ProcessRunner.Outcome out = ProcessRunner.run(pb, Duration.ofSeconds(30));
        return new Result(out.exitCode(), out.stdout(), out.stderr());
    }
}
