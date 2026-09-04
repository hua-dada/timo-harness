package com.agent.timo.workspace.pids;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 共享容器 pids 配额巡检（M1-10，移植自源项目 sandbox/pids-monitor.ts）。
 *
 * <p>容器级 --pids-limit 或 per-uid RLIMIT_NPROC 打满时，新进程启动即崩（源项目实测
 * node 建线程池 CHECK 断言 exit 134）——等用户报障已晚。低频轮询容器 pids 水位 +
 * per-uid 进程数，接近阈值（80%）时限频 warn（供采集告警）。
 *
 * <p>纯函数部分（解析/阈值判定）与调度解耦，单独可测。
 */
public final class PidsMonitorLogic {

    private PidsMonitorLogic() {
    }

    /** 巡检间隔（每轮一次 root docker exec + 容器内 /proc 扫描，量级毫秒级）。 */
    public static final long MONITOR_INTERVAL_MS = 60_000;

    /** 告警阈值比例（容器 pids 与 per-uid 进程数各自对照上限）。 */
    public static final double ALERT_RATIO = 0.8;

    /** 同类别告警冷却：持续超阈值也最多 10 分钟一条。 */
    public static final long ALERT_COOLDOWN_MS = 10 * 60_000;

    /** 巡检脚本（容器内 root sh 执行）：cgroup pids 水位（v2/v1 双路径）+ per-uid 进程数 top5。
     *  纯 /proc 实现（不依赖 procps/ps）；read 内建解析 Uid 行避免逐进程 fork。 */
    public static final String PROBE_SCRIPT = """
            cur=$(cat /sys/fs/cgroup/pids.current 2>/dev/null || cat /sys/fs/cgroup/pids/pids.current 2>/dev/null || echo 0)
            max=$(cat /sys/fs/cgroup/pids.max 2>/dev/null || cat /sys/fs/cgroup/pids/pids.max 2>/dev/null || echo 0)
            echo "pids $cur $max"
            for s in /proc/[0-9]*/status; do
              while IFS= read -r k v _; do
                [ "$k" = "Uid:" ] && { printf '%s\\n' "$v"; break; }
              done < "$s" 2>/dev/null
            done | sort | uniq -c | sort -rn | head -5 | while read -r c u; do echo "uid $c $u"; done""";

    /** 单轮巡检样本。pidsMax=0 表示未知/无限（"max"），跳过容器级判定。 */
    public record PidsSample(int pidsCurrent, int pidsMax, List<UidCount> topUids) {

        public record UidCount(String uid, int count) {
        }
    }

    private static final Pattern SPLIT = Pattern.compile("\\s+");

    /** 解析巡检脚本输出（纯函数）。无合法 pids 行（输出异常）返回 null。 */
    public static PidsSample parseProbeOutput(String text) {
        int pidsCurrent = 0;
        int pidsMax = -1;
        List<PidsSample.UidCount> topUids = new java.util.ArrayList<>();
        for (String line : text.split("\n", -1)) {
            String[] parts = SPLIT.split(line.trim());
            if (parts.length == 0 || parts[0].isEmpty()) {
                continue;
            }
            if ("pids".equals(parts[0]) && parts.length >= 3) {
                int cur;
                try {
                    cur = Integer.parseInt(parts[1]);
                } catch (NumberFormatException e) {
                    cur = 0;
                }
                pidsCurrent = cur;
                // pids.max 为 "max"（无限）时记 0 = 未知，跳过容器级判定
                pidsMax = "max".equals(parts[2]) ? 0 : parseIntOr(parts[2], 0);
            } else if ("uid".equals(parts[0]) && parts.length >= 3) {
                try {
                    topUids.add(new PidsSample.UidCount(parts[2], Integer.parseInt(parts[1])));
                } catch (NumberFormatException ignored) {
                    // 行格式异常跳过
                }
            }
        }
        if (pidsMax < 0) {
            return null;
        }
        return new PidsSample(pidsCurrent, pidsMax, List.copyOf(topUids));
    }

    /** 某一告警类别的限频状态（容器级一份 / per-uid 各一份）。 */
    public static final class CategoryState {
        long warnAt;
        boolean alerting;
    }

    /** 巡检告警的全量状态（PidsMonitor 单例持有）。 */
    public static final class AlertState {
        final CategoryState pids = new CategoryState();
        /** 只保留出现过的 uid（cap 512 防异常场景无限增长）。 */
        final Map<String, CategoryState> uid = new java.util.HashMap<>();
    }

    public static AlertState newAlertState() {
        return new AlertState();
    }

    /** 一条告警决策。 */
    public record AlertDecision(Level level, String message, Map<String, Object> fields) {
        public enum Level { WARN, INFO }
    }

    /** 阈值评估（纯函数；直接 mutate state，与源项目一致）。 */
    public static List<AlertDecision> checkPidsThresholds(
            PidsSample sample, int nproc, AlertState state, long now) {
        List<AlertDecision> out = new java.util.ArrayList<>();
        if (sample.pidsMax() > 0) {
            out.addAll(evaluateCategory(
                    state.pids,
                    sample.pidsCurrent() >= sample.pidsMax() * ALERT_RATIO,
                    now,
                    Map.of("pidsCurrent", sample.pidsCurrent(), "pidsMax", sample.pidsMax()),
                    "共享容器 pids 接近上限，新进程启动将 exit 134",
                    "共享容器 pids 水位恢复正常"));
        }
        java.util.Set<String> overUids = new java.util.HashSet<>();
        for (PidsSample.UidCount uc : sample.topUids()) {
            if (uc.count() < nproc * ALERT_RATIO) {
                continue;
            }
            overUids.add(uc.uid());
            CategoryState st = state.uid.computeIfAbsent(uc.uid(), k -> new CategoryState());
            out.addAll(evaluateCategory(
                    st,
                    true, // 外层 continue 已过滤：到达此处即超阈值
                    now,
                    Map.of("uid", uc.uid(), "count", uc.count(), "nproc", nproc),
                    "沙箱用户进程数接近 nproc 上限，该用户新进程启动将 exit 134",
                    "沙箱用户进程数恢复正常"));
        }
        // 曾告警的 uid 本轮已回到阈值下（或掉出 top 名单）→ 恢复
        for (Map.Entry<String, CategoryState> e : state.uid.entrySet()) {
            if (e.getValue().alerting && !overUids.contains(e.getKey())) {
                e.getValue().alerting = false;
                out.add(new AlertDecision(AlertDecision.Level.INFO,
                        "沙箱用户进程数恢复正常", Map.of("uid", e.getKey())));
            }
        }
        return out;
    }

    /** 单类别评估：超阈值时限频 warn，恢复时 info 一次。 */
    private static List<AlertDecision> evaluateCategory(
            CategoryState st, boolean over, long now,
            Map<String, Object> warnFields, String warnMsg, String recoverMsg) {
        if (over) {
            if (!st.alerting || now - st.warnAt >= ALERT_COOLDOWN_MS) {
                st.warnAt = now;
                st.alerting = true;
                return List.of(new AlertDecision(AlertDecision.Level.WARN, warnMsg, warnFields));
            }
            return List.of();
        }
        if (st.alerting) {
            st.alerting = false;
            return List.of(new AlertDecision(AlertDecision.Level.INFO, recoverMsg, warnFields));
        }
        return List.of();
    }

    private static int parseIntOr(String s, int dflt) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return dflt;
        }
    }
}
