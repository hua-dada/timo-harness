package com.apeloa.agent.workspace.pids;

import static org.assertj.core.api.Assertions.assertThat;

import com.apeloa.agent.workspace.DockerCli;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PidsMonitorTest {

    /** 可拨动的时钟（fail 限频与告警冷却都用 wall time）。 */
    private static final class MutableClock extends Clock {
        volatile long millis;

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis);
        }
    }

    private static final class FakeCli implements DockerCli {
        volatile DockerCli.Result result = new DockerCli.Result(0, "", "");
        List<String> lastArgs;

        @Override
        public DockerCli.Result run(List<String> args) {
            lastArgs = List.copyOf(args);
            return result;
        }
    }

    @Test
    void 巡检argv_超阈值warn出口() {
        FakeCli cli = new FakeCli();
        cli.result = new DockerCli.Result(0, "pids 900 1000\n", "");
        List<PidsMonitorLogic.AlertDecision> seen = new ArrayList<>();
        MutableClock clock = new MutableClock();

        PidsMonitor monitor = new PidsMonitor(cli, "pi-shared-java", 500, seen::add, clock);
        monitor.probeOnce();

        assertThat(cli.lastArgs).containsExactly(
                "exec", "pi-shared-java", "sh", "-c", PidsMonitorLogic.PROBE_SCRIPT);
        assertThat(seen).hasSize(1);
        assertThat(seen.get(0).level()).isEqualTo(PidsMonitorLogic.AlertDecision.Level.WARN);
        assertThat(seen.get(0).fields()).containsEntry("pidsCurrent", 900).containsEntry("pidsMax", 1000);
    }

    @Test
    void 巡检失败计数_成功后清零() {
        FakeCli cli = new FakeCli();
        cli.result = new DockerCli.Result(1, "", "docker daemon unreachable");
        List<PidsMonitorLogic.AlertDecision> seen = new ArrayList<>();
        MutableClock clock = new MutableClock();

        PidsMonitor monitor = new PidsMonitor(cli, "pi-shared-java", 500, seen::add, clock);
        monitor.probeOnce();
        monitor.probeOnce();
        assertThat(monitor.failStreak()).isEqualTo(2);
        assertThat(seen).isEmpty();

        cli.result = new DockerCli.Result(0, "pids 10 4096\n", "");
        monitor.probeOnce();
        assertThat(monitor.failStreak()).isZero();
        // 水位正常，无告警出口
        assertThat(seen).isEmpty();
    }

    @Test
    void 输出异常时静默放弃本轮() {
        FakeCli cli = new FakeCli();
        cli.result = new DockerCli.Result(0, "garbage output\n", "");
        List<PidsMonitorLogic.AlertDecision> seen = new ArrayList<>();

        PidsMonitor monitor = new PidsMonitor(cli, "pi-shared-java", 500, seen::add, new MutableClock());
        monitor.probeOnce();
        assertThat(seen).isEmpty();
        assertThat(monitor.failStreak()).isZero();
    }

    @Test
    void start停止幂等() {
        FakeCli cli = new FakeCli();
        PidsMonitor monitor = new PidsMonitor(cli, "pi-shared-java");
        monitor.start();
        monitor.start();
        monitor.stop();
        monitor.stop();
        assertThat(cli.lastArgs).isNull();
    }
}
