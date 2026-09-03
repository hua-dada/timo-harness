package com.apeloa.agent.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.apeloa.agent.chat.persist.ChatPersistenceService;
import com.apeloa.agent.chat.persist.SessionMapper;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.message.AssistantMessage;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.UserMessage;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * M1-11 验收门「重启后会话完整」：跑完一轮后丢弃全部内存会话（模拟重启/驱逐），从 DB 重建
 * ——列表仍含该会话（标题已回填）、SSE 全量重建取回真实 messages_loaded、且会话仍可继续对话。
 * 模型上下文恢复（AgentStateStore 重载）是框架行为，这里验证其 DB 侧档位已就绪（M1-5/M1-14
 * 再以真模型端到端复核）。
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(ChatRebuildAfterRestartTest.StubAgents.class)
class ChatRebuildAfterRestartTest {

    /** 覆盖 AgentScopeChatAgentFactory（@Component），否则注入歧义（与 SSE 集成测试同款）。 */
    @TestConfiguration
    static class StubAgents {

        @Bean
        @Primary
        StubChatAgentFactory stubChatAgentFactory() {
            return new StubChatAgentFactory();
        }
    }

    private static final String USER = "r1";
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @Autowired private StubChatAgentFactory agents;

    @Autowired private ChatPersistenceService persistence;

    @Autowired private SessionMapper sessionMapper;

    @Autowired private JdbcTemplate jdbc;

    @Autowired private AgentSessionManager manager;

    private AgentSessionManager restarted;

    @BeforeEach
    void seed() {
        jdbc.update(
                "INSERT INTO org_accounts (username) VALUES (?) ON CONFLICT DO NOTHING", USER);
    }

    @AfterEach
    void cleanup() {
        if (restarted != null) {
            restarted.shutdown();
        }
        jdbc.update("DELETE FROM sessions WHERE user_id = ?", USER);
        agents.reset();
    }

    @Test
    void 重启后会话列表历史与续聊全部恢复() throws Exception {
        // --- 阶段一：原进程跑完一轮（name 缺省 → 默认标题，首条消息后回填） ---
        AgentSession original = manager.create(USER, null);
        String sessionId = original.sessionId();
        original.send("帮我看看文件");
        StubChatAgentFactory.Run run = agents.awaitRun(TIMEOUT);
        agents.setCommittedContext(
                List.of(
                        UserMessage.builder().textContent("帮我看看文件").build(),
                        AssistantMessage.builder()
                                .content(text("我看下，先 ls"))
                                .build()));
        run.emit(
                new TextBlockDeltaEvent("run", "b1", "我看下，先 ls"),
                new AgentEndEvent("run"));
        run.complete();
        awaitIdle(original);

        // --- 阶段二：模拟重启——全新 manager（内存空，DB 为权威） ---
        restarted = new AgentSessionManager(agents, persistence, sessionMapper, 100, 30);

        List<SessionSummary> list = restarted.list(USER);
        assertThat(list).singleElement().satisfies(summary -> {
            assertThat(summary.sessionId()).isEqualTo(sessionId);
            assertThat(summary.name()).isEqualTo("帮我看看文件"); // 首条用户消息回填
            assertThat(summary.state()).isEqualTo("idle");
        });

        AgentSession rebuilt = restarted.require(USER, sessionId);
        SessionSubscription subscription = rebuilt.subscribe(null);
        assertThat(subscription.fullRebuild()).isTrue();
        // messages_loaded 载荷 = DB 历史投影（loadHistory 解析成 Map），非空且顺序正确
        assertThat(subscription.rebuildHistory()).hasSize(2);
        @SuppressWarnings("unchecked")
        Map<String, Object> first = (Map<String, Object>) subscription.rebuildHistory().get(0);
        @SuppressWarnings("unchecked")
        Map<String, Object> second = (Map<String, Object>) subscription.rebuildHistory().get(1);
        assertThat(first)
                .containsEntry("role", "user")
                .containsEntry("content", "帮我看看文件");
        assertThat(second).containsEntry("role", "assistant");
        // 重启后缓冲里没有可补发的历史尾巴（全部已落盘）
        assertThat(subscription.poll(Duration.ofMillis(200).toMillis())).isNull();
        subscription.close();

        // --- 阶段三：重建的会话仍可继续对话 ---
        rebuilt.send("再来一条");
        StubChatAgentFactory.Run next = agents.awaitRun(TIMEOUT);
        assertThat(next.message().getTextContent()).isEqualTo("再来一条");
        agents.setCommittedContext(
                List.of(
                        UserMessage.builder().textContent("帮我看看文件").build(),
                        AssistantMessage.builder().content(text("我看下，先 ls")).build(),
                        UserMessage.builder().textContent("再来一条").build(),
                        AssistantMessage.builder().content(text("好")).build()));
        next.emit(new AgentEndEvent("run"));
        next.complete();
        awaitIdle(rebuilt);

        List<Object> history = persistence.loadHistory(sessionId);
        assertThat(history).hasSize(4);
    }

    /** 等会话状态落回 IDLE：agent_end 帧先于状态与 entries 落盘落地（与 SSE 集成测试同口径）。 */
    private static void awaitIdle(AgentSession session) throws Exception {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            if (session.state() == AgentSession.State.IDLE) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("会话迟迟没回 IDLE：" + session.sessionId());
    }

    private static TextBlock text(String s) {
        return TextBlock.builder().text(s).build();
    }
}
