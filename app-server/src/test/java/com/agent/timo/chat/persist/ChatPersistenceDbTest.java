package com.agent.timo.chat.persist;

import static org.assertj.core.api.Assertions.assertThat;

import com.agent.timo.chat.ChatPersistence;
import io.agentscope.core.message.AssistantMessage;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultMessage;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.state.AgentState;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * M1-11 持久化层集成测试（真 PG 测试库，test profile）：AgentState 整档往返、entries 增量
 * 落盘游标 / 链 / 标题回填、级联删除。依赖 {@code agent_timo_test} 库可达（见
 * application-test.yml 注释）。
 */
@SpringBootTest
@ActiveProfiles("test")
class ChatPersistenceDbTest {

    private static final String USER = "p1";

    @Autowired private DbAgentStateStore stateStore;

    @Autowired private ChatPersistenceService persistence;

    @Autowired private SessionMapper sessionMapper;

    @Autowired private SessionEntryMapper entryMapper;

    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        jdbc.update(
                "INSERT INTO org_accounts (username) VALUES (?) ON CONFLICT DO NOTHING", USER);
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM sessions WHERE user_id = ?", USER);
    }

    @Test
    void AgentState整档往返与按用户列举删除() {
        String sessionId = newSessionRow();
        AgentState state =
                AgentState.builder()
                        .userId(USER)
                        .sessionId(sessionId)
                        .addMessage(UserMessage.builder().textContent("第一句").build())
                        .build();

        stateStore.save(USER, sessionId, DbAgentStateStore.AGENT_STATE_KEY, state);

        Optional<AgentState> back =
                stateStore.get(USER, sessionId, DbAgentStateStore.AGENT_STATE_KEY, AgentState.class);
        assertThat(back).isPresent();
        assertThat(back.get().getContext())
                .singleElement()
                .satisfies(msg -> assertThat(msg.getTextContent()).isEqualTo("第一句"));
        assertThat(stateStore.exists(USER, sessionId)).isTrue();
        assertThat(stateStore.listSessionIds(USER)).contains(sessionId);

        stateStore.delete(USER, sessionId);
        assertThat(stateStore.exists(USER, sessionId)).isFalse();
        assertThat(stateStore.get(USER, sessionId, DbAgentStateStore.AGENT_STATE_KEY, AgentState.class))
                .isEmpty();
    }

    @Test
    void List形态按JSON数组往返() {
        String sessionId = newSessionRow();
        List<Msg> messages =
                List.of(
                        UserMessage.builder().textContent("a").build(),
                        UserMessage.builder().textContent("b").build());

        stateStore.save(USER, sessionId, "msgs", messages);

        List<Msg> back = stateStore.getList(USER, sessionId, "msgs", Msg.class);
        assertThat(back).hasSize(2);
        assertThat(back.get(1).getTextContent()).isEqualTo("b");
    }

    @Test
    void 增量落盘推进游标接链并回填标题() {
        String sessionId = newSessionRow();
        List<Msg> context =
                List.of(
                        UserMessage.builder().textContent("帮我看看文件").build(),
                        AssistantMessage.builder()
                                .content(
                                        text("我看下"),
                                        new ToolUseBlock("tc1", "bash", Map.of("command", "ls")))
                                .build());

        ChatPersistence.SyncOutcome first = persistence.persistNewTurns(sessionId, context);

        assertThat(first.cursor()).isEqualTo(2);
        assertThat(first.title()).isEqualTo("帮我看看文件");

        List<SessionEntryEntity> rows = entryMapper.listOrdered(UUID.fromString(sessionId));
        assertThat(rows).hasSize(2);
        assertThat(rows).extracting(SessionEntryEntity::getKind).containsExactly("user", "assistant");
        // 线性链：第二行 parent = 第一行 id；游标与链尾随事务推进
        assertThat(rows.get(1).getParentId()).isEqualTo(rows.get(0).getId());
        SessionEntity row = sessionMapper.selectById(UUID.fromString(sessionId));
        assertThat(row.getPersistedMsgs()).isEqualTo(2);
        assertThat(row.getLastEntryId()).isEqualTo(rows.get(1).getId());
        assertThat(row.getTitle()).isEqualTo("帮我看看文件");

        // 上下文增长：只落新增尾巴
        List<Msg> grown =
                List.of(
                        context.get(0),
                        context.get(1),
                        ToolResultMessage.builder()
                                .content(ToolResultBlock.of("tc1", "bash", text("a.txt")))
                                .build());
        ChatPersistence.SyncOutcome second = persistence.persistNewTurns(sessionId, grown);
        assertThat(second.cursor()).isEqualTo(3);
        assertThat(second.title()).isNull(); // 已回填过，不再覆盖
        assertThat(entryMapper.listOrdered(UUID.fromString(sessionId))).hasSize(3);

        // 同一上下文重放：幂等不重不漏
        assertThat(persistence.persistNewTurns(sessionId, grown).cursor()).isEqualTo(3);
        assertThat(entryMapper.listOrdered(UUID.fromString(sessionId))).hasSize(3);

        // 历史回放按落盘顺序返回前端形状
        List<Object> history = persistence.loadHistory(sessionId);
        assertThat(history).hasSize(3);
        assertThat(history.get(2))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("role", "toolResult")
                .containsEntry("toolCallId", "tc1");
    }

    @Test
    void 删会话级联清entries与状态() {
        String sessionId = newSessionRow();
        persistence.persistNewTurns(
                sessionId, List.of(UserMessage.builder().textContent("你好").build()));
        stateStore.save(
                USER,
                sessionId,
                DbAgentStateStore.AGENT_STATE_KEY,
                AgentState.builder().userId(USER).sessionId(sessionId).build());

        sessionMapper.deleteById(UUID.fromString(sessionId));

        assertThat(entryMapper.listOrdered(UUID.fromString(sessionId))).isEmpty();
        assertThat(stateStore.exists(USER, sessionId)).isFalse();
    }

    @Test
    void 会话行已删时落盘静默放弃() {
        String gone = UUID.randomUUID().toString();

        ChatPersistence.SyncOutcome outcome =
                persistence.persistNewTurns(
                        gone, List.of(UserMessage.builder().textContent("孤儿").build()));

        assertThat(outcome.cursor()).isEqualTo(-1);
        assertThat(persistence.loadHistory(gone)).isEmpty();
    }

    private static io.agentscope.core.message.TextBlock text(String s) {
        return io.agentscope.core.message.TextBlock.builder().text(s).build();
    }

    /** 建一行最小 sessions（对齐 Manager.create 的落行形状）。 */
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
