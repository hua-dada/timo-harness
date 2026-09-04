package com.agent.timo.chat.replay;

import static org.assertj.core.api.Assertions.assertThat;

import com.agent.timo.chat.persist.AgentStateMapper;
import com.agent.timo.chat.persist.DbAgentStateStore;
import com.agent.timo.chat.persist.SessionEntity;
import com.agent.timo.chat.persist.SessionMapper;
import com.agent.timo.core.tools.ReadTool;
import com.agent.timo.core.tools.WriteTool;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.Model;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.tool.Toolkit;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * <b>M1-5 / PoC-A 的真模型实证</b>：{@link SessionReplayConsistencyPocTest} 用脚本模型证明了
 * 「重放后模型可见 prompt 逐条逐块相同」这个机制层判据；这里补上端到端的软证据——真模型在
 * <b>整档复制出来的新会话</b>里，仅凭被 fork 过来的历史就能答对上一轮写进文件的内容。
 *
 * <p>默认<b>不</b>随套件跑：需网络与真实令牌，故用 {@code POC_LIVE=1} 显式开启
 * （网关不可达或令牌失效时应当直接失败，不做静默降级——实证的意义就在于真链路）。
 * 跑法：{@code POC_LIVE=1 mvn -pl app-server -am -Dtest=SessionReplayConsistencyLiveTest
 * -Dsurefire.failIfNoSpecifiedTests=false test}。
 *
 * <p>profile 取 {@code test,poc}：test 提供真 PG 测试库与 {@code DbAgentStateStore}，poc 提供
 * 指向 new-api 网关的 {@code Model} bean（见 application-poc.yml）。{@code AgentScopePoCRunner}
 * 的 prompt 在这里置空，避免起上下文时额外打一次 M1-4 的模型调用。
 */
@SpringBootTest(properties = "app.poc.agentscope.prompt=")
@ActiveProfiles({"test", "poc"})
@EnabledIfEnvironmentVariable(named = "POC_LIVE", matches = "1")
class SessionReplayConsistencyLiveTest {

    private static final Logger log = LoggerFactory.getLogger(SessionReplayConsistencyLiveTest.class);

    private static final String USER = "poc-a-live";
    private static final String SYS_PROMPT = "你是测试助手。工作区里的文件请用工具读写，不要凭空猜内容。";
    /** 真模型 + 工具两轮，留足网关往返时间。 */
    private static final Duration TIMEOUT = Duration.ofMinutes(3);

    @TempDir Path workspace;

    @Autowired private DbAgentStateStore stateStore;
    @Autowired private AgentStateMapper stateMapper;
    @Autowired private SessionMapper sessionMapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private Model model;

    private StateFork fork;

    @BeforeEach
    void seed() {
        jdbc.update("INSERT INTO org_accounts (username) VALUES (?) ON CONFLICT DO NOTHING", USER);
        fork = new StateFork(stateMapper, USER);
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM sessions WHERE user_id = ?", USER);
    }

    @Test
    void 真模型在整档复制出的会话里仅凭fork来的历史就答对文件内容() {
        String source = newSessionRow();
        ReActAgent agent = agent();

        // 第一轮：真模型调工具把内容写进工作区（真落盘）
        String written = "口令是 " + UUID.randomUUID().toString().substring(0, 8);
        String firstAnswer =
                run(agent, source, "请用工具把 secret.txt 写成这一行内容，然后回复「已写入」：" + written);
        log.info("PoC-A live 第一轮回复：{}", firstAnswer);
        assertThat(workspace.resolve("secret.txt")).exists();

        List<Msg> sourceContext = contextOf(agent, source);
        assertThat(sourceContext).as("第一轮应已落盘 user/assistant/tool 若干条").hasSizeGreaterThan(2);

        // 把文件从工作区删掉：口令此后只存在于被 fork 过来的历史里，模型再想「读一下确认」也读不到，
        // 于是第二轮答对与否就只能归因于历史是否完整可见——这是本实证的决定性设计。
        deleteWorkspaceFile("secret.txt");

        // fork：整档复制到新会话（新会话此前从未与模型交互过）
        String forked = fork.whole(source, newSessionRow());
        assertThat(fork.doc(forked).get("context")).hasSize(sourceContext.size());

        // 第二轮在 fork 会话里追问，且明确要求不要再读文件——答对只能来自被 fork 过来的历史
        String answer =
                run(
                        agent,
                        forked,
                        "不要使用任何工具，直接根据我们之前的对话回答：刚才写进 secret.txt 的那一行内容是什么？");
        log.info("PoC-A live fork 追问回复：{}", answer);

        // 判据：模型复述出的正是上一轮的口令 —— 历史穿过 jsonb 整档复制后对模型仍然可见且可用
        assertThat(answer).contains(written.substring(written.length() - 8));

        // fork 只长自己：源会话不被新回合污染
        assertThat(contextOf(agent, forked)).hasSizeGreaterThan(sourceContext.size());
        assertThat(contextOf(agent, source)).hasSize(sourceContext.size());
    }

    // ------------------------------------------------------------- 夹具

    private ReActAgent agent() {
        // 工具必须在 build 之前注册（M1-4 实测：Agent 构造时快照工具表）。
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new WriteTool(workspace));
        toolkit.registerTool(new ReadTool(workspace));
        return ReActAgent.builder()
                .name("poc-a-live")
                .sysPrompt(SYS_PROMPT)
                .model(model)
                .toolkit(toolkit)
                .maxIters(8)
                .stateStore(stateStore)
                .defaultSessionId(UUID.randomUUID().toString())
                .build();
    }

    /** 跑一轮到底并返回终态文本；blockLast 返回时状态已落盘（save 挂在 reply mono 链上）。 */
    private String run(ReActAgent agent, String sessionId, String prompt) {
        agent.streamEvents(
                        UserMessage.builder().textContent(prompt).build(),
                        RuntimeContext.builder().userId(USER).sessionId(sessionId).build())
                .blockLast(TIMEOUT);
        List<Msg> context = contextOf(agent, sessionId);
        return context.isEmpty() ? "" : context.get(context.size() - 1).getTextContent();
    }

    private List<Msg> contextOf(ReActAgent agent, String sessionId) {
        AgentState state = agent.getAgentState(USER, sessionId);
        return state == null ? List.of() : state.getContext();
    }

    private void deleteWorkspaceFile(String name) {
        try {
            java.nio.file.Files.delete(workspace.resolve(name));
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException("清理工作区文件失败：" + name, e);
        }
    }

    private String newSessionRow() {
        SessionEntity row = new SessionEntity();
        row.setId(UUID.randomUUID());
        row.setUserId(USER);
        row.setTitle("新会话");
        OffsetDateTime now = OffsetDateTime.now();
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        sessionMapper.insert(row);
        return row.getId().toString();
    }
}
