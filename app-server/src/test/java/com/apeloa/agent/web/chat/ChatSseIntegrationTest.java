package com.apeloa.agent.web.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.apeloa.agent.chat.StubChatAgentFactory;
import com.apeloa.agent.chat.StubChatAgentFactory.Run;
import com.apeloa.agent.web.chat.SseTestClient.Frame;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallDeltaEvent;
import io.agentscope.core.event.ToolCallEndEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultStartEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.AssistantMessage;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatUsage;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;

/**
 * M1-6 端到端：真起 HTTP + 真读 SSE 字节流，Agent 用 {@link StubChatAgentFactory} 替掉（离线、
 * 事件时序由用例逐条投递）。刻意不走 MockMvc——本任务交付物正是 SSE 帧序、{@code id:} 单调、
 * {@code Last-Event-ID} 补发与断连行为，这些只有真连接才测得出。
 *
 * <p>断言用 {@code contains} 而非反序列化：Boot 4 下 Jackson 2/3 并存，测试侧不引包更省事，
 * 且这里要盯的就是下发给前端的原始文本。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(ChatSseIntegrationTest.StubAgents.class)
class ChatSseIntegrationTest {

    /** 覆盖 {@code AgentScopeChatAgentFactory}（@Component），否则注入歧义。 */
    @TestConfiguration
    static class StubAgents {

        @Bean
        @Primary
        StubChatAgentFactory stubChatAgentFactory() {
            return new StubChatAgentFactory();
        }
    }

    /**
     * M1-11 起会话落 PG：用到的用户须有 org_accounts 行（FK），用例产生的 sessions 行
     * 结束后清掉（entries / agent_states 级联），测试库不积脏数据。
     */
    private static final List<String> USERS =
            java.util.stream.Stream.concat(
                            java.util.stream.IntStream.rangeClosed(1, 11).mapToObj(i -> "u" + i),
                            java.util.stream.Stream.of("owner1", "other1"))
                    .toList();

    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final Pattern SESSION_ID = Pattern.compile("\"sessionId\"\\s*:\\s*\"([^\"]+)\"");

    /** 触发 HITL 的危险调用（toolCallId 即 HITL requestId）。 */
    private static final ToolUseBlock DANGEROUS =
            new ToolUseBlock("tc1", "bash", Map.of("command", "rm -rf /tmp/x"));

    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    @Autowired private StubChatAgentFactory agents;

    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;

    @Value("${local.server.port}")
    private int port;

    @BeforeEach
    void resetStub() {
        agents.reset();
        USERS.forEach(
                user -> jdbc.update(
                        "INSERT INTO org_accounts (username) VALUES (?) ON CONFLICT DO NOTHING",
                        user));
    }

    @AfterEach
    void clearSessions() {
        USERS.forEach(user -> jdbc.update("DELETE FROM sessions WHERE user_id = ?", user));
    }

    @Test
    void 握手帧无id且全量重建才带messages_loaded() throws Exception {
        String user = "u1";
        String sessionId = createSession(user);

        try (SseTestClient sse = connect(sessionId, user, null)) {
            assertThat(sse.response().headers().firstValue("Content-Type").orElse(""))
                    .startsWith("text/event-stream");
            assertThat(sse.response().headers().firstValue("Cache-Control").orElse(""))
                    .contains("no-store");
            assertThat(sse.response().headers().firstValue("X-Accel-Buffering").orElse(""))
                    .isEqualTo("no");
            expectHandshake(sse, sessionId, true);
        }
    }

    @Test
    void 一回合事件按序下发且id会话内单调() throws Exception {
        String user = "u2";
        String sessionId = createSession(user);

        try (SseTestClient sse = connect(sessionId, user, null)) {
            expectHandshake(sse, sessionId, true);
            assertThat(send(sessionId, user, "列一下文件").statusCode()).isEqualTo(200);

            Run run = agents.awaitRun(TIMEOUT);
            assertThat(run.message().getTextContent()).isEqualTo("列一下文件");
            run.emit(new TextBlockDeltaEvent("run", "b1", "我看看"));
            emitBashCall(run, "ls");
            run.emit(
                    new ToolResultStartEvent("run", "tc1", "bash"),
                    new ToolResultTextDeltaEvent("run", "tc1", "bash", "a.txt"),
                    new ToolResultEndEvent("run", "tc1", "bash", ToolResultState.SUCCESS),
                    new AgentResultEvent(
                            AssistantMessage.builder()
                                    .textContent("我看看")
                                    .usage(ChatUsage.builder().inputTokens(100).outputTokens(50).build())
                                    .build()),
                    new AgentEndEvent("run"));
            run.complete();

            expect(sse, "prompt_accepted", 1);
            assertThat(expect(sse, "text_delta", 2).data()).contains("我看看");
            assertThat(expect(sse, "tool_start", 3).data()).contains("bash");
            assertThat(expect(sse, "tool_args", 4).data()).contains("ls");
            assertThat(expect(sse, "tool_end", 5).data()).contains("a.txt").contains("\"isError\":false");
            assertThat(expect(sse, "message_usage", 6).data()).contains("150");
            expect(sse, "agent_end", 7);
        }
    }

    @Test
    void 窗内Last_Event_ID只补断点之后且不重发messages_loaded() throws Exception {
        String user = "u3";
        String sessionId = createSession(user);

        Run run;
        try (SseTestClient first = connect(sessionId, user, null)) {
            expectHandshake(first, sessionId, true);
            assertThat(send(sessionId, user, "你好").statusCode()).isEqualTo(200);
            run = agents.awaitRun(TIMEOUT);
            run.emit(new TextBlockDeltaEvent("run", "b1", "第一段"));
            expect(first, "prompt_accepted", 1);
            expect(first, "text_delta", 2);
        }
        // 断线期间照跑：seq 3、4 只落缓冲，等重连补发。
        run.emit(
                new TextBlockDeltaEvent("run", "b1", "第二段"),
                new TextBlockDeltaEvent("run", "b1", "第三段"));

        try (SseTestClient again = connect(sessionId, user, "2")) {
            expectHandshake(again, sessionId, false);
            assertThat(expect(again, "text_delta", 3).data()).contains("第二段");
            assertThat(expect(again, "text_delta", 4).data()).contains("第三段");
            // 补发完无缝接实时流
            run.emit(new AgentEndEvent("run"));
            run.complete();
            expect(again, "agent_end", 5);
        }
    }

    @Test
    void 非法Last_Event_ID按缺失处理走全量重建() throws Exception {
        String user = "u4";
        String sessionId = createSession(user);

        try (SseTestClient sse = connect(sessionId, user, "abc")) {
            expectHandshake(sse, sessionId, true);
        }
    }

    @Test
    void HITL批准后带ConfirmResult续跑同一回合() throws Exception {
        String user = "u5";
        String sessionId = createSession(user);

        try (SseTestClient sse = connect(sessionId, user, null)) {
            expectHandshake(sse, sessionId, true);
            assertThat(send(sessionId, user, "清理临时目录").statusCode()).isEqualTo(200);

            Run first = agents.awaitRun(TIMEOUT);
            emitBashCall(first, "rm -rf /tmp/x");
            first.emit(
                    new RequireUserConfirmEvent("reply-1", List.of(DANGEROUS)),
                    new AgentResultEvent(asking()),
                    new AgentEndEvent("run"));
            first.complete();

            expect(sse, "prompt_accepted", 1);
            expect(sse, "tool_start", 2);
            expect(sse, "tool_args", 3);
            assertThat(expect(sse, "ui_request", 4).data())
                    .contains("\"method\":\"confirm\"")
                    .contains("tc1")
                    .contains("rm -rf /tmp/x");
            // 暂停不是回合结束：agent_end 被吞掉，前端保持 streaming 等应答
            expect(sse, "message_usage", 5);

            HttpResponse<String> busy = send(sessionId, user, "顺便看看日志");
            assertThat(busy.statusCode()).isEqualTo(409);
            assertThat(busy.body()).contains("等待人工确认");

            assertThat(hitl("tc1", user, "{\"action\":\"approve\"}").statusCode()).isEqualTo(200);

            Run resumed = agents.awaitRun(TIMEOUT);
            List<ConfirmResult> results = confirmResults(resumed);
            assertThat(results).hasSize(1);
            assertThat(results.get(0).isConfirmed()).isTrue();
            assertThat(results.get(0).getToolCall().getInput())
                    .isEqualTo(Map.of("command", "rm -rf /tmp/x"));

            resumed.emit(
                    new ToolResultStartEvent("run", "tc1", "bash"),
                    new ToolResultTextDeltaEvent("run", "tc1", "bash", "已删除"),
                    new ToolResultEndEvent("run", "tc1", "bash", ToolResultState.SUCCESS),
                    new AgentEndEvent("run"));
            resumed.complete();
            assertThat(expect(sse, "tool_end", 6).data()).contains("已删除");
            expect(sse, "agent_end", 7);
        }
    }

    @Test
    void HITL改参回传新args且id与名字不变() throws Exception {
        String user = "u6";
        String sessionId = createSession(user);

        try (SseTestClient sse = connect(sessionId, user, null)) {
            expectHandshake(sse, sessionId, true);
            assertThat(send(sessionId, user, "清理临时目录").statusCode()).isEqualTo(200);
            Run first = agents.awaitRun(TIMEOUT);
            first.emit(new RequireUserConfirmEvent("reply-1", List.of(DANGEROUS)), new AgentEndEvent("run"));
            first.complete();
            expect(sse, "prompt_accepted", 1);
            expect(sse, "ui_request", 2);

            HttpResponse<String> missingArgs = hitl("tc1", user, "{\"action\":\"modify\"}");
            assertThat(missingArgs.statusCode()).isEqualTo(400);
            assertThat(missingArgs.body()).contains("modify 必须带 args");

            String modify = "{\"action\":\"modify\",\"args\":{\"command\":\"rm -rf /tmp/safe\"}}";
            assertThat(hitl("tc1", user, modify).statusCode()).isEqualTo(200);

            ConfirmResult result = confirmResults(agents.awaitRun(TIMEOUT)).get(0);
            assertThat(result.isConfirmed()).isTrue();
            assertThat(result.getToolCall().getId()).isEqualTo("tc1");
            assertThat(result.getToolCall().getName()).isEqualTo("bash");
            assertThat(result.getToolCall().getInput()).isEqualTo(Map.of("command", "rm -rf /tmp/safe"));
        }
    }

    @Test
    void HITL拒绝补发tool_end并回传拒绝结果() throws Exception {
        String user = "u7";
        String sessionId = createSession(user);

        try (SseTestClient sse = connect(sessionId, user, null)) {
            expectHandshake(sse, sessionId, true);
            assertThat(send(sessionId, user, "清理临时目录").statusCode()).isEqualTo(200);
            Run first = agents.awaitRun(TIMEOUT);
            first.emit(new RequireUserConfirmEvent("reply-1", List.of(DANGEROUS)), new AgentEndEvent("run"));
            first.complete();
            expect(sse, "prompt_accepted", 1);
            expect(sse, "ui_request", 2);

            HttpResponse<String> badAction = hitl("tc1", user, "{\"action\":\"nope\"}");
            assertThat(badAction.statusCode()).isEqualTo(400);
            assertThat(badAction.body()).contains("action 必须是");

            assertThat(hitl("tc1", user, "{\"action\":\"reject\"}").statusCode()).isEqualTo(200);

            // 框架拒绝路径不发 ToolResultEndEvent，tool_end 由会话自行补，否则工具卡永远转圈
            assertThat(expect(sse, "tool_end", 3).data())
                    .contains("用户已拒绝执行")
                    .contains("\"isError\":true");

            Run resumed = agents.awaitRun(TIMEOUT);
            assertThat(confirmResults(resumed).get(0).isConfirmed()).isFalse();
            resumed.emit(new AgentEndEvent("run"));
            resumed.complete();
            expect(sse, "agent_end", 4);
        }
    }

    @Test
    void 流式中再发prompt回409且回合结束后可继续() throws Exception {
        String user = "u8";
        String sessionId = createSession(user);

        try (SseTestClient sse = connect(sessionId, user, null)) {
            expectHandshake(sse, sessionId, true);
            assertThat(send(sessionId, user, "第一条").statusCode()).isEqualTo(200);
            Run run = agents.awaitRun(TIMEOUT);
            expect(sse, "prompt_accepted", 1);

            HttpResponse<String> second = send(sessionId, user, "第二条");
            assertThat(second.statusCode()).isEqualTo(409);
            assertThat(second.body()).contains("上一条消息仍在处理");

            run.emit(new AgentEndEvent("run"));
            run.complete();
            expect(sse, "agent_end", 2);

            awaitIdle(sessionId, user);
            assertThat(send(sessionId, user, "第三条").statusCode()).isEqualTo(200);
            expect(sse, "prompt_accepted", 3);
        }
    }

    @Test
    void abort中止在途回合并补error与agent_end() throws Exception {
        String user = "u9";
        String sessionId = createSession(user);

        try (SseTestClient sse = connect(sessionId, user, null)) {
            expectHandshake(sse, sessionId, true);
            assertThat(send(sessionId, user, "跑个大任务").statusCode()).isEqualTo(200);
            agents.awaitRun(TIMEOUT);
            expect(sse, "prompt_accepted", 1);

            assertThat(post("/api/sessions/" + sessionId + "/abort", user, "{}").statusCode())
                    .isEqualTo(200);

            assertThat(expect(sse, "error", 2).data()).contains("已中止");
            expect(sse, "agent_end", 3);
        }
    }

    @Test
    void 归属与入参校验分别回404_401_400() throws Exception {
        String owner = "owner1";
        String sessionId = createSession(owner);

        HttpResponse<String> foreign = send(sessionId, "other1", "你好");
        assertThat(foreign.statusCode()).isEqualTo(404);
        assertThat(foreign.body()).contains("会话不存在");

        HttpResponse<String> anonymous = post("/api/sessions", "..", "{}");
        assertThat(anonymous.statusCode()).isEqualTo(401);
        assertThat(anonymous.body()).contains("未登录");
        // SSE 通道同样先鉴权，未登录不开流
        assertThat(get("/api/sessions/" + sessionId + "/events", "..").statusCode()).isEqualTo(401);

        HttpResponse<String> unknown = hitl("nope", owner, "{\"action\":\"approve\"}");
        assertThat(unknown.statusCode()).isEqualTo(404);
        assertThat(unknown.body()).contains("确认请求不存在");

        HttpResponse<String> blank = send(sessionId, owner, "   ");
        assertThat(blank.statusCode()).isEqualTo(400);
        assertThat(blank.body()).contains("message 不能为空");
    }

    @Test
    void 会话列表只列当前用户且带状态() throws Exception {
        String user = "u10";
        String mine = createSession(user);
        String theirs = createSession("u11");

        HttpResponse<String> list = get("/api/sessions", user);

        assertThat(list.statusCode()).isEqualTo(200);
        assertThat(list.body()).contains(mine).contains("\"state\":\"idle\"").doesNotContain(theirs);
    }

    private String base() {
        return "http://localhost:" + port;
    }

    private String createSession(String userId) throws Exception {
        HttpResponse<String> response = post("/api/sessions", userId, "{\"name\":\"测试会话\"}");
        assertThat(response.statusCode()).isEqualTo(200);
        Matcher matcher = SESSION_ID.matcher(response.body());
        assertThat(matcher.find()).as("建会话响应缺 sessionId：%s", response.body()).isTrue();
        return matcher.group(1);
    }

    private SseTestClient connect(String sessionId, String userId, String lastEventId) throws Exception {
        SseTestClient sse =
                new SseTestClient(base() + "/api/sessions/" + sessionId + "/events", userId, lastEventId);
        assertThat(sse.response().statusCode()).isEqualTo(200);
        return sse;
    }

    private HttpResponse<String> post(String path, String userId, String json) throws Exception {
        HttpRequest request =
                HttpRequest.newBuilder(URI.create(base() + path))
                        .header("X-User-Id", userId)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                        .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> send(String sessionId, String userId, String text) throws Exception {
        return post(
                "/api/sessions/" + sessionId + "/messages",
                userId,
                "{\"message\":\"" + text + "\"}");
    }

    private HttpResponse<String> hitl(String requestId, String userId, String json) throws Exception {
        return post("/api/hitl/" + requestId, userId, json);
    }

    private HttpResponse<String> get(String path, String userId) throws Exception {
        HttpRequest request =
                HttpRequest.newBuilder(URI.create(base() + path)).header("X-User-Id", userId).GET().build();
        return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    /** 消费握手帧：session_info + thinking_level 恒无 id（不能推进客户端 Last-Event-ID）。 */
    private void expectHandshake(SseTestClient sse, String sessionId, boolean fullRebuild)
            throws InterruptedException {
        Frame info = sse.nextFrame(TIMEOUT);
        assertThat(info.event()).isEqualTo("session_info");
        assertThat(info.id()).isNull();
        assertThat(info.data()).contains(sessionId).contains("测试会话");

        Frame level = sse.nextFrame(TIMEOUT);
        assertThat(level.event()).isEqualTo("thinking_level");
        assertThat(level.id()).isNull();

        if (fullRebuild) {
            // 全量重建才发：源 reducer 收到它会整体替换消息并清 streaming，窗内续传绝不能带。
            Frame loaded = sse.nextFrame(TIMEOUT);
            assertThat(loaded.event()).isEqualTo("messages_loaded");
            assertThat(loaded.id()).isNull();
            assertThat(loaded.data()).contains("[]");
        }
    }

    /** 断言下一帧的 event 名与 seq（{@code id:} 即会话内单调序号）。 */
    private Frame expect(SseTestClient sse, String event, long seq) throws InterruptedException {
        Frame frame = sse.nextFrame(TIMEOUT);
        assertThat(frame.event()).as("期望 %s#%d，实到 %s", event, seq, frame).isEqualTo(event);
        assertThat(frame.id()).isEqualTo(Long.toString(seq));
        return frame;
    }

    @SuppressWarnings("unchecked")
    private static List<ConfirmResult> confirmResults(Run run) {
        Object raw = run.message().getMetadata().get(Msg.METADATA_CONFIRM_RESULTS);
        assertThat(raw).as("续跑消息未带 ConfirmResults").isInstanceOf(List.class);
        return (List<ConfirmResult>) raw;
    }

    /** 等会话状态落回 IDLE：agent_end 帧先于状态落地，紧接着发下一条会被判 409。 */
    private void awaitIdle(String sessionId, String userId) throws Exception {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            String body = get("/api/sessions", userId).body();
            if (body.contains(sessionId) && body.contains("\"state\":\"idle\"")) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("会话迟迟没回 IDLE：" + sessionId);
    }

    /** 一次「模型要调 bash」的事件脚本（参数只在 ToolCallDeltaEvent 里，见 AgentEventMapper）。 */
    private static void emitBashCall(Run run, String command) {
        run.emit(
                new ToolCallStartEvent("run", "tc1", "bash"),
                new ToolCallDeltaEvent("run", "tc1", "bash", "{\"command\":\"" + command + "\"}"),
                new ToolCallEndEvent("run", "tc1", "bash"));
    }

    /** 权限暂停时框架的终态消息（无用量、终因 PERMISSION_ASKING）。 */
    private static AssistantMessage asking() {
        return AssistantMessage.builder()
                .textContent("需要确认")
                .generateReason(GenerateReason.PERMISSION_ASKING)
                .build();
    }
}
