package com.apeloa.agent.workspace.pids;

import com.apeloa.agent.workspace.DockerCli;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * pids 巡检调度壳（M1-10）：周期 {@code docker exec <容器> sh -c PROBE_SCRIPT}（root），
 * 解析 + 阈值判定委托 {@link PidsMonitorLogic}（纯函数，单测在那边）。移植自源项目
 * pids-monitor.ts 的 startPidsMonitor/probeOnce。
 *
 * <p>告警出口为可注入 sink（生产默认 slf4j），巡检失败限频：首次 + 每 30×冷却期一条 warn，
 * 防 docker 抖动刷屏。线程 daemon，不阻塞 JVM 退出。
 */
public final class PidsMonitor {

    private static final Logger log = LoggerFactory.getLogger(PidsMonitor.class);

    /** per-uid 对照上限 = prlimit nproc（与 DockerArgs 注入的 RLIMIT_NPROC 同源同默认）。 */
    public static final int DEFAULT_NPROC = 512;

    /** 巡检失败再告警间隔（源项目：30 × 告警冷却期）。 */
    static final long FAIL_WARN_INTERVAL_MS = 30 * PidsMonitorLogic.ALERT_COOLDOWN_MS;

    private final DockerCli docker;
    private final String container;
    private final int nproc;
    private final Consumer<PidsMonitorLogic.AlertDecision> sink;
    private final Clock clock;
    private final PidsMonitorLogic.AlertState state = PidsMonitorLogic.newAlertState();

    private ScheduledExecutorService timer;
    private int failStreak;
    private long lastFailWarnAt;

    public PidsMonitor(DockerCli docker, String container, int nproc,
            Consumer<PidsMonitorLogic.AlertDecision> sink, Clock clock) {
        this.docker = docker;
        this.container = container;
        this.nproc = nproc;
        this.sink = sink != null ? sink : PidsMonitor::logDecision;
        this.clock = clock;
    }

    public PidsMonitor(DockerCli docker, String container) {
        this(docker, container, DEFAULT_NPROC, null, Clock.systemUTC());
    }

    /** 启动周期巡检（幂等；60s 一轮，首轮延迟一个周期）。 */
    public synchronized void start() {
        if (timer != null) {
            return;
        }
        timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "pids-monitor");
            t.setDaemon(true);
            return t;
        });
        timer.scheduleAtFixedRate(this::probeSafely,
                PidsMonitorLogic.MONITOR_INTERVAL_MS, PidsMonitorLogic.MONITOR_INTERVAL_MS,
                TimeUnit.MILLISECONDS);
    }

    public synchronized void stop() {
        if (timer != null) {
            timer.shutdownNow();
            timer = null;
        }
    }

    // —— 内部 ——

    private void probeSafely() {
        try {
            probeOnce();
        } catch (RuntimeException e) {
            // docker daemon 故障等：限频 warn（probeOnce 内部已处理 code≠0，这里兜异常）。
            failStreak++;
            warnFail(-1, e.toString());
        }
    }

    /** 单轮巡检（包内可见供单测直调）。 */
    void probeOnce() {
        DockerCli.Result r = docker.run(List.of("exec", container, "sh", "-c",
                PidsMonitorLogic.PROBE_SCRIPT));
        if (r.code() != 0) {
            failStreak++;
            if (failStreak == 1 || clock.millis() - lastFailWarnAt >= FAIL_WARN_INTERVAL_MS) {
                warnFail(r.code(), r.stderr());
            }
            return;
        }
        failStreak = 0;
        PidsMonitorLogic.PidsSample sample = PidsMonitorLogic.parseProbeOutput(r.stdout());
        if (sample == null) {
            return; // 输出异常（无合法 pids 行），本轮放弃
        }
        for (PidsMonitorLogic.AlertDecision d :
                PidsMonitorLogic.checkPidsThresholds(sample, nproc, state, clock.millis())) {
            sink.accept(d);
        }
    }

    private void warnFail(int code, String detail) {
        lastFailWarnAt = clock.millis();
        String clipped = detail == null ? "" : detail.substring(0, Math.min(200, detail.length()));
        log.warn("pids 巡检失败 (code={}): {}", code, clipped);
    }

    int failStreak() {
        return failStreak;
    }

    private static void logDecision(PidsMonitorLogic.AlertDecision d) {
        if (d.level() == PidsMonitorLogic.AlertDecision.Level.WARN) {
            log.warn("{} {}", d.message(), d.fields());
        } else {
            log.info("{} {}", d.message(), d.fields());
        }
    }
}
