package com.apeloa.agent.workspace;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 有状态 fake docker（单测用）：inspect 结果可预设；run 成功后容器视为 running，
 * unpause/start 同步状态——模拟真实 docker 的可观察行为，避免用例对线程时序敏感。
 */
final class RecordingDockerCli implements DockerCli {

    final List<String> calls = new CopyOnWriteArrayList<>();
    /** 容器状态（running/paused/exited…）；null = 容器不存在。 */
    volatile String status;

    @Override
    public Result run(List<String> args) {
        calls.add(String.join(" ", args));
        switch (args.get(0)) {
            case "inspect":
                return status != null
                        ? new Result(0, status + "\n", "")
                        : new Result(1, "", "Error: No such object: " + args.get(args.size() - 1));
            case "run":
                status = "running";
                return new Result(0, "container-id\n", "");
            case "unpause":
            case "start":
                status = "running";
                return new Result(0, "", "");
            default:
                return new Result(0, "", "");
        }
    }

    long count(String prefix) {
        return calls.stream().filter(c -> c.startsWith(prefix)).count();
    }
}
