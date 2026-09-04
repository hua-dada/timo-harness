package com.agent.timo.workspace;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 所有 docker CLI 调用收敛于此：SandboxManager / 巡检经本接口操作容器，不直接 spawn docker
 * （收敛副作用，便于单测与后续替换 K8s provider）。移植自源项目 sandbox/docker.ts。
 *
 * <p>失败语义：code≠0 抛 {@link DockerError}；inspect 的「容器不存在」返回 null
 * （仅当 stderr 匹配 No such）。
 */
public interface DockerCli {

    /** docker 命令结果。 */
    record Result(int code, String stdout, String stderr) {
    }

    /** docker 命令失败（code≠0 且非 NotFound）。 */
    class DockerError extends RuntimeException {
        public DockerError(String cmd, Result result) {
            super("docker " + cmd + " 失败 (code=" + result.code() + "): "
                    + result.stderr().trim());
        }
    }

    /** 同步执行 docker 命令（阻塞至完成）。 */
    Result run(List<String> args);

    /** 查容器状态（docker 原生：running / paused / exited / created / …）。
     *  容器不存在 → null；其它错误（daemon 故障等）→ 抛 {@link DockerError}。 */
    default String inspectStatus(String name) {
        Result r = run(List.of("inspect", "-f", "{{.State.Status}}", name));
        if (r.code() == 0) {
            return r.stdout().trim();
        }
        if (Pattern.compile("no such (container|object)", Pattern.CASE_INSENSITIVE)
                .matcher(r.stderr()).find()) {
            return null;
        }
        throw new DockerError("inspect", r);
    }

    /** code≠0 抛 DockerError。 */
    default Result checked(String cmd, List<String> args) {
        Result r = run(args);
        if (r.code() != 0) {
            throw new DockerError(cmd, r);
        }
        return r;
    }

    default void start(String name) {
        checked("start", List.of("start", name));
    }

    default void stop(String name) {
        checked("stop", List.of("stop", name));
    }

    default void pause(String name) {
        checked("pause", List.of("pause", name));
    }

    default void unpause(String name) {
        checked("unpause", List.of("unpause", name));
    }

    /** 强制销毁容器（rm -f，即便 running 也删）。 */
    default void remove(String name) {
        checked("rm", List.of("rm", "-f", name));
    }
}
