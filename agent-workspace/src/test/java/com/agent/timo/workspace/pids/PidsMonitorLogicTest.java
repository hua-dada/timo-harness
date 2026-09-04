package com.agent.timo.workspace.pids;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** 移植自源项目 sandbox/pids-monitor.test.ts。 */
class PidsMonitorLogicTest {

    // —— parseProbeOutput ——

    @Test
    void 解析pids水位与perUid计数() {
        PidsMonitorLogic.PidsSample s =
                PidsMonitorLogic.parseProbeOutput("pids 1234 4096\nuid 450 100123\nuid 12 0\n");
        assertThat(s.pidsCurrent()).isEqualTo(1234);
        assertThat(s.pidsMax()).isEqualTo(4096);
        assertThat(s.topUids()).containsExactly(
                new PidsMonitorLogic.PidsSample.UidCount("100123", 450),
                new PidsMonitorLogic.PidsSample.UidCount("0", 12));
    }

    @Test
    void pidsMax为max无限时记0_无pids行返回null() {
        PidsMonitorLogic.PidsSample s = PidsMonitorLogic.parseProbeOutput("pids 10 max\n");
        assertThat(s.pidsCurrent()).isEqualTo(10);
        assertThat(s.pidsMax()).isZero();
        assertThat(s.topUids()).isEmpty();

        assertThat(PidsMonitorLogic.parseProbeOutput("uid 5 100123\n")).isNull();
        assertThat(PidsMonitorLogic.parseProbeOutput("")).isNull();
    }

    // —— checkPidsThresholds ——

    private static final int NPROC = 500;

    private PidsMonitorLogic.PidsSample sample(int pidsCurrent,
            PidsMonitorLogic.PidsSample.UidCount... topUids) {
        return new PidsMonitorLogic.PidsSample(pidsCurrent, 1000, List.of(topUids));
    }

    @Test
    void 未达阈值80百分比无告警() {
        PidsMonitorLogic.AlertState st = PidsMonitorLogic.newAlertState();
        var r = PidsMonitorLogic.checkPidsThresholds(
                sample(799, new PidsMonitorLogic.PidsSample.UidCount("1", 399)),
                NPROC, st, 0);
        assertThat(r).isEmpty();
    }

    @Test
    void 超阈值warn_冷却期内不刷屏_超冷却期再warn_恢复info一次() {
        PidsMonitorLogic.AlertState st = PidsMonitorLogic.newAlertState();

        var first = PidsMonitorLogic.checkPidsThresholds(sample(900), NPROC, st, 0);
        assertThat(first).hasSize(1);
        assertThat(first.get(0).level()).isEqualTo(PidsMonitorLogic.AlertDecision.Level.WARN);

        // 冷却期内（<10min）：不重复
        assertThat(PidsMonitorLogic.checkPidsThresholds(sample(950), NPROC, st, 5 * 60_000))
                .isEmpty();
        // 超冷却期（≥10min）：再 warn 并刷新 warnAt
        var again = PidsMonitorLogic.checkPidsThresholds(sample(950), NPROC, st, 11 * 60_000);
        assertThat(again).hasSize(1);
        assertThat(again.get(0).level()).isEqualTo(PidsMonitorLogic.AlertDecision.Level.WARN);

        // 恢复：info 一条，此后静默
        var recover = PidsMonitorLogic.checkPidsThresholds(sample(100), NPROC, st, 12 * 60_000);
        assertThat(recover).hasSize(1);
        assertThat(recover.get(0).level()).isEqualTo(PidsMonitorLogic.AlertDecision.Level.INFO);
        assertThat(PidsMonitorLogic.checkPidsThresholds(sample(100), NPROC, st, 13 * 60_000))
                .isEmpty();
    }

    @Test
    void pidsMax为0未知无限跳过容器级判定() {
        PidsMonitorLogic.AlertState st = PidsMonitorLogic.newAlertState();
        var r = PidsMonitorLogic.checkPidsThresholds(
                new PidsMonitorLogic.PidsSample(99999, 0, List.of()), NPROC, st, 0);
        assertThat(r).isEmpty();
    }

    @Test
    void perUid超nproc阈值告警_掉出名单后恢复info() {
        PidsMonitorLogic.AlertState st = PidsMonitorLogic.newAlertState();
        var hit = PidsMonitorLogic.checkPidsThresholds(
                sample(100, new PidsMonitorLogic.PidsSample.UidCount("100123", 450)),
                NPROC, st, 0);
        assertThat(hit).hasSize(1);
        assertThat(hit.get(0).level()).isEqualTo(PidsMonitorLogic.AlertDecision.Level.WARN);
        assertThat(hit.get(0).fields()).containsEntry("uid", "100123")
                .containsEntry("count", 450)
                .containsEntry("nproc", NPROC);

        // 该 uid 掉出 top 名单（进程已被清理）→ 恢复
        var recover = PidsMonitorLogic.checkPidsThresholds(sample(100), NPROC, st, 60_000);
        assertThat(recover).hasSize(1);
        assertThat(recover.get(0).level()).isEqualTo(PidsMonitorLogic.AlertDecision.Level.INFO);
        assertThat(recover.get(0).fields()).containsEntry("uid", "100123");
    }
}
