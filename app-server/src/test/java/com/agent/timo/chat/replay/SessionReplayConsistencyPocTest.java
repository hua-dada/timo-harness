package com.agent.timo.chat.replay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agent.timo.chat.persist.AgentStateMapper;
import com.agent.timo.chat.persist.ChatPersistenceService;
import com.agent.timo.chat.persist.DbAgentStateStore;
import com.agent.timo.chat.persist.SessionEntity;
import com.agent.timo.chat.persist.SessionMapper;
import com.agent.timo.core.tools.ReadTool;
import com.agent.timo.core.tools.WriteTool;
import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.util.JsonUtils;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * <b>M1-5 / PoC-A：会话消息重放一致性</b>（PRD 风险 #3、M2-7 fork 的前置结论）。
 *
 * <p>判据不是「回答像不像」，而是<b>模型可见上下文是否等同</b>：ReActAgent 每轮把
 * {@code [system] + AgentState.context} 交给 {@code Model.stream}（ReActAgent#reasoning →
 * prependSystemMsg），所以只要重放后那份 prompt 与原会话逐条逐块相同，模型行为就没有
 * 「重放漂移」的空间。{@link ScriptedModel} 把每轮 prompt 原样录下来，断言即等值比较。
 *
 * <p>用真 {@link ReActAgent} + 真 {@link DbAgentStateStore} + 真工具（临时 workspace 真落盘），
 * 只替换模型：被验证的正是框架自己的 载入 → 追加 → 落盘 闭环。
 *
 * <p>三条被验证的结论：
 * <ol>
 *   <li><b>整档复制可重放</b>（clone / 全量 fork）：复制 {@code agent_states.payload} 到新
 *       sessionId，模型看到的历史与源会话一致，工具调用参数与工具结果穿过 jsonb 无损。
 *   <li><b>前缀截断可重放</b>（从任意 user 消息重新生成）：截断 context 后重发该消息，prompt
 *       与原会话当轮<b>逐条相同</b>——这是 M2-7「fork 删尾」的正确性依据。
 *   <li><b>entries 不能当重放源</b>：{@code session_entries} 是前端有损投影，反序列化回
 *       {@code Msg} 直接失败；模型上下文的唯一权威是 {@code agent_states}。
 * </ol>
 */
@SpringBootTest
@ActiveProfiles("test")
class SessionReplayConsistencyPocTest {

    private static final String USER = "poc-a";
    private static final String SYS_PROMPT = "你是测试助手。";
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    @TempDir Path workspace;

    @Autowired private DbAgentStateStore stateStore;
    @Autowired private AgentStateMapper stateMapper;
    @Autowired private SessionMapper sessionMapper;
    @Autowired private ChatPersistenceService persistence;
    @Autowired private JdbcTemplate jdbc;

    private final ScriptedModel model = new ScriptedModel();
    private StateFork fork;

    @BeforeEach
    void seed() {
        jdbc.update("INSERT INTO org_accounts (username) VALUES (?) ON CONFLICT DO NOTHING", USER);
        fork = new StateFork(stateMapper, USER);
        model.reset();
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM sessions WHERE user_id = ?", USER);
    }

    // ------------------------------------------------------------- 结论 ①

    @Test
    void 整档复制到新会话后模型看到的历史与源会话一致() {
        String source = newSessionRow();
        ReActAgent agent = agent();
        twoToolRounds(agent, source);

        // 源会话跑完：8 条 context（user / assistant(toolCall) / tool / assistant(text) ×2）
        List<Msg> sourceContext = contextOf(agent, source);
        assertThat(sourceContext).hasSize(8);
        List<Msg> sourceLastPrompt = model.promptAt(model.callCount());

        // fork：整档复制 payload 到新 sessionId（内嵌 session_id 一并改写，见 StateFork 注释）
        String forked = fork.whole(source, newSessionRow());
        assertThat(contextOf(agent, source)).hasSize(8); // 复制不动源会话

        model.enqueue(TextBlock.builder().text("内容是 hi。").build());
        run(agent, forked, "刚才写进去的是什么？");

        List<Msg> forkedPrompt = model.promptAt(model.callCount());
        assertThat(forkedPrompt).hasSize(sourceContext.size() + 2); // +system +新 user

        // 判据：去掉新消息后逐条逐块等于「源会话最后一轮 prompt + 其收尾 assistant」，
        // 即完整历史无缺、无重、无变形。
        List<String> replayed =
                PromptSignature.of(forkedPrompt.subList(0, forkedPrompt.size() - 1));
        List<String> expected =
                PromptSignature.of(concat(sourceLastPrompt, List.of(sourceContext.get(7))));
        assertThat(replayed).containsExactlyElementsOf(expected);

        // 工具调用参数与工具结果穿过 jsonb 往返无损（重放的实质就是这些块必须原样回来）
        assertThat(replayed)
                .anySatisfy(s -> assertThat(s).contains("toolCall:write_file{content=hi,path=hello.txt}"))
                .anySatisfy(s -> assertThat(s).contains("toolResult:write_file:SUCCESS"))
                .anySatisfy(s -> assertThat(s).contains("toolCall:read_file{path=hello.txt}"));

        // 框架按槽位写回：新增两条只进 fork 会话，源会话不受污染
        assertThat(contextOf(agent, forked)).hasSize(10);
        assertThat(contextOf(agent, source)).hasSize(8);
    }

    // ------------------------------------------------------------- 结论 ②

    @Test
    void 前缀截断重放的prompt与原会话当轮逐条相同() {
        String source = newSessionRow();
        ReActAgent agent = agent();
        twoToolRounds(agent, source);

        // 原会话第二轮的首次模型调用 = 第 3 次（轮一用掉 2 次：推理 + 工具后续）
        List<Msg> originalRound2Prompt = model.promptAt(3);
        assertThat(contextOf(agent, source)).hasSize(8);

        // fork 删尾：截到轮二 user 之前（前 4 条 = 轮一完整），再重发该消息
        String regenerated = fork.truncated(source, newSessionRow(), 4);
        model.enqueue(ScriptedModel.toolCall("call-r2b", "read_file", Map.of("path", "hello.txt")));
        model.enqueue(TextBlock.builder().text("内容还是 hi。").build());
        run(agent, regenerated, "再看一下内容");

        // 判据：重新生成那轮，模型看到的 prompt 与原会话当轮逐条逐块相同
        List<Msg> replayPrompt = model.promptAt(model.callCount() - 1);
        assertThat(PromptSignature.of(replayPrompt))
                .containsExactlyElementsOf(PromptSignature.of(originalRound2Prompt));

        // 截断生效：原轮二的尾巴没被带过来（4 保留 + 4 本轮重生成）
        assertThat(contextOf(agent, regenerated)).hasSize(8);
        assertThat(contextOf(agent, source)).hasSize(8);
    }

    // ------------------------------------------------------------- 结论 ③

    @Test
    void entries是前端有损投影不能当重放源() {
        String session = newSessionRow();
        ReActAgent agent = agent();
        twoToolRounds(agent, session);
        persistence.persistNewTurns(session, contextOf(agent, session));

        // agent_states：框架形状，context 里是完整 Msg 文档
        JsonNode stateDoc = fork.doc(session);
        assertThat(stateDoc.get("context")).hasSize(8);

        // session_entries：前端形状（toolCallId / isError / details），不是 Msg
        Object toolResultRow =
                persistence.loadHistory(session).stream()
                        .filter(row -> row instanceof Map<?, ?> m && "toolResult".equals(m.get("role")))
                        .findFirst()
                        .orElseThrow();
        assertThat(toolResultRow)
                .asInstanceOf(InstanceOfAssertFactories.MAP)
                .containsKey("toolCallId")
                .containsKey("isError")
                .containsEntry("details", null);

        // 决定性事实：这一行喂不回框架——MsgRole 没有 toolResult，反序列化直接失败。
        // 故 M2-7 的 fork 必须复制 agent_states；entries 只服务前端会话树展示。
        String rowJson = StateFork.stringify(toolResultRow);
        assertThatThrownBy(() -> JsonUtils.getJsonCodec().fromJson(rowJson, Msg.class))
                .isInstanceOf(RuntimeException.class);
    }

    // ------------------------------------------------------------- 夹具

    /** 两轮真工具调用：写文件 → 读文件，每轮 = 工具调用 + 收尾文本。 */
    private void twoToolRounds(ReActAgent agent, String sessionId) {
        model.enqueue(
                ScriptedModel.toolCall("call-1", "write_file", Map.of("path", "hello.txt", "content", "hi")));
        model.enqueue(TextBlock.builder().text("写好了。").build());
        run(agent, sessionId, "把 hello.txt 写成 hi");

        model.enqueue(ScriptedModel.toolCall("call-2", "read_file", Map.of("path", "hello.txt")));
        model.enqueue(TextBlock.builder().text("内容是 hi。").build());
        run(agent, sessionId, "再看一下内容");
    }

    private ReActAgent agent() {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new WriteTool(workspace));
        toolkit.registerTool(new ReadTool(workspace));
        return ReActAgent.builder()
                .name("poc-a")
                .sysPrompt(SYS_PROMPT)
                .model(model)
                .toolkit(toolkit)
                .maxIters(8)
                .stateStore(stateStore)
                .defaultSessionId(UUID.randomUUID().toString())
                .build();
    }

    /** 跑一轮到底；blockLast 返回时状态已落盘（save 挂在 reply mono 链上，非 fire-and-forget）。 */
    private void run(ReActAgent agent, String sessionId, String prompt) {
        agent.streamEvents(
                        UserMessage.builder().textContent(prompt).build(),
                        RuntimeContext.builder().userId(USER).sessionId(sessionId).build())
                .blockLast(TIMEOUT);
    }

    private List<Msg> contextOf(ReActAgent agent, String sessionId) {
        AgentState state = agent.getAgentState(USER, sessionId);
        return state == null ? List.of() : state.getContext();
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

    private static List<Msg> concat(List<Msg> head, List<Msg> tail) {
        List<Msg> all = new ArrayList<>(head);
        all.addAll(tail);
        return all;
    }
}
