package com.agent.timo.core.bash;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 危险命令识别：对 bash 工具的命令做高危规则匹配。
 *
 * <p>移植自源项目 apps/server/src/dangerous-commands.ts 的 9 条规则，语义对齐：
 * <b>宁可漏报不可误报</b>——误报污染审计告警，让人忽略真告警。刻意放过
 * {@code rm -rf ./build}、{@code rm file} 等正常操作，只拦真正高危目标。
 *
 * <p>与源项目的差异：源项目"只检测不拦截"（事后审计）；Java 版在 M1-8 升级为
 * {@link BashPermissionGate} 的输入——命中 → ASK（HITL 人工确认）或 DENY，未命中放行。
 *
 * <p>未移植 extractCommand()：TS 版需从 unknown 形态的 args 里防御性挖命令字符串，
 * Java 侧 BashTool 的参数是类型化的 String，无需该层。
 */
public final class DangerousCommandDetector {

    private record Rule(String rule, String label, Pattern pattern) {
    }

    // rm 的 -rf 顺序多变（-rf / -fr / -rvf…），用 r…f | f…r 两种顺序覆盖。
    private static final List<Rule> RULES = List.of(
            new Rule("rm-rf-root", "递归强删根/家目录或绝对系统路径",
                    Pattern.compile("\\brm\\s+-[a-zA-Z]*(?:r[a-zA-Z]*f|f[a-zA-Z]*r)[a-zA-Z]*\\s+(?:/[^\\s]*|~(?:[/\\s]|$))")),
            new Rule("rm-rf-dot", "递归强删当前/上级目录",
                    Pattern.compile("\\brm\\s+-[a-zA-Z]*(?:r[a-zA-Z]*f|f[a-zA-Z]*r)[a-zA-Z]*\\s+(?:\\.(?:\\s|$)|\\.\\.(?:\\s|$|/))")),
            new Rule("priv-esc", "提权（sudo/su）",
                    Pattern.compile("(?:^|[;&|]\\s*)(?:sudo|su)\\s")),
            new Rule("chmod-777", "全员可写（chmod 777）",
                    Pattern.compile("\\bchmod\\s+[-R]*\\s*777\\b")),
            new Rule("fork-bomb", "fork 炸弹",
                    Pattern.compile(":\\(\\)\\s*\\{\\s*:\\s*\\|\\s*:\\s*&\\s*\\}\\s*;\\s*:")),
            new Rule("dd-dev", "写裸设备（dd of=/dev/）",
                    Pattern.compile("\\bdd\\b[^|;&]*of=/dev/")),
            new Rule("mkfs-dev", "格式化设备（mkfs /dev/）",
                    Pattern.compile("\\bmkfs(?:\\.\\w+)?\\s+/dev/")),
            new Rule("remote-exec", "远程脚本管道执行（curl/wget | sh）",
                    Pattern.compile("\\b(?:curl|wget)\\b[^|;&]*\\|\\s*(?:sh|bash|zsh)\\b")),
            new Rule("write-passwd", "写系统账户文件（/etc/passwd|shadow）",
                    Pattern.compile(">\\s*/etc/(?:passwd|shadow)\\b")));

    /** 对命令字符串做危险规则匹配，返回所有命中（空列表=未命中）。 */
    public List<DangerousHit> detect(String command) {
        List<DangerousHit> hits = new ArrayList<>();
        if (command == null || command.isEmpty()) {
            return hits;
        }
        for (Rule r : RULES) {
            Matcher m = r.pattern().matcher(command);
            if (m.find()) {
                hits.add(new DangerousHit(r.rule(), r.label(), m.group()));
            }
        }
        return hits;
    }
}
