package com.agent.timo.chat;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.AllToolsDeniedEvent;
import io.agentscope.core.event.ExceedMaxItersEvent;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.TextBlockEndEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockStartEvent;
import io.agentscope.core.event.ToolCallDeltaEvent;
import io.agentscope.core.event.ToolCallEndEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultStartEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.AssistantMessage;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatUsage;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * M1-6：AgentScope 事件 → ChatDelta 投影矩阵（含工具参数/结果的片段累积与 HITL 暂停语义）。
 */
class AgentEventMapperTest {

    @Test
    void 文本与思考增量投影且空增量忽略() {
        AgentEventMapper mapper = new AgentEventMapper();

        assertThat(mapper.map(new ThinkingBlockStartEvent("r1", "b1")))
                .containsExactly(ChatDelta.ThinkingStart.of());
        assertThat(mapper.map(new ThinkingBlockDeltaEvent("r1", "b1", "想")))
                .containsExactly(ChatDelta.ThinkingDelta.of("想"));
        assertThat(mapper.map(new TextBlockDeltaEvent("r1", "b2", "答")))
                .containsExactly(ChatDelta.TextDelta.of("答"));
        assertThat(mapper.map(new TextBlockDeltaEvent("r1", "b2", ""))).isEmpty();
        assertThat(mapper.map(new ThinkingBlockDeltaEvent("r1", "b1", null))).isEmpty();
        // Block End / ModelCall* 等噪声事件不投影
        assertThat(mapper.map(new TextBlockEndEvent("r1", "b2"))).isEmpty();
    }

    @Test
    void 工具入参按片段累积到ToolCallEnd才产出tool_args() {
        AgentEventMapper mapper = new AgentEventMapper();

        assertThat(mapper.map(new ToolCallStartEvent("r1", "tc1", "bash")))
                .containsExactly(ChatDelta.ToolStart.of("tc1", "bash", null));
        assertThat(mapper.map(new ToolCallDeltaEvent("r1", "tc1", "bash", "{\"command\":"))).isEmpty();
        assertThat(mapper.map(new ToolCallDeltaEvent("r1", "tc1", "bash", "\"ls\"}"))).isEmpty();

        assertThat(mapper.map(new ToolCallEndEvent("r1", "tc1", "bash")))
                .containsExactly(ChatDelta.ToolArgs.of("tc1", Map.of("command", "ls")));
    }

    @Test
    void 入参非法JSON按原文下发() {
        AgentEventMapper mapper = new AgentEventMapper();
        mapper.map(new ToolCallStartEvent("r1", "tc1", "bash"));
        mapper.map(new ToolCallDeltaEvent("r1", "tc1", "bash", "{\"command\":"));

        assertThat(mapper.map(new ToolCallEndEvent("r1", "tc1", "bash")))
                .containsExactly(ChatDelta.ToolArgs.of("tc1", "{\"command\":"));
    }

    @Test
    void 工具结果按片段累积到ToolResultEnd才产出tool_end() {
        AgentEventMapper mapper = new AgentEventMapper();
        mapper.map(new ToolResultStartEvent("r1", "tc1", "bash"));
        assertThat(mapper.map(new ToolResultTextDeltaEvent("r1", "tc1", "bash", "a.txt\n"))).isEmpty();
        mapper.map(new ToolResultTextDeltaEvent("r1", "tc1", "bash", "b.txt"));

        assertThat(mapper.map(new ToolResultEndEvent("r1", "tc1", "bash", ToolResultState.SUCCESS)))
                .containsExactly(ChatDelta.ToolEnd.of("tc1", "bash", false, "a.txt\nb.txt"));
    }

    @Test
    void 非SUCCESS状态置isError且无文本时按状态补结论() {
        AgentEventMapper mapper = new AgentEventMapper();

        // 拒绝路径：框架直接写结果块，不发文本增量
        assertThat(mapper.map(new ToolResultEndEvent("r1", "tc1", "bash", ToolResultState.DENIED)))
                .containsExactly(ChatDelta.ToolEnd.of("tc1", "bash", true, "用户已拒绝执行"));
        assertThat(mapper.map(new ToolResultEndEvent("r1", "tc2", "bash", ToolResultState.ERROR)))
                .containsExactly(ChatDelta.ToolEnd.of("tc2", "bash", true, "工具执行失败"));
    }

    @Test
    void 权限确认发ui_request并吞掉本轮agent_end() {
        AgentEventMapper mapper = new AgentEventMapper();
        ToolUseBlock call =
                new ToolUseBlock("tc1", "bash", Map.of("command", "rm -rf /tmp/x"));

        List<ChatDelta> deltas = mapper.map(new RequireUserConfirmEvent("r1", List.of(call)));

        assertThat(deltas)
                .containsExactly(
                        ChatDelta.UiRequest.of(
                                UiRequestPayload.confirm("tc1", "确认执行 bash：rm -rf /tmp/x")));
        assertThat(mapper.awaitingConfirm()).isTrue();
        assertThat(mapper.confirmReplyId()).isEqualTo("r1");
        assertThat(mapper.confirmToolCalls()).containsExactly(call);
        // 暂停不是回合结束：吞掉 agent_end，前端保持 streaming 等应答续跑
        assertThat(mapper.map(new AgentEndEvent("r1"))).isEmpty();
    }

    @Test
    void 无暂停时agent_end正常投影() {
        assertThat(new AgentEventMapper().map(new AgentEndEvent("r1")))
                .containsExactly(ChatDelta.AgentEnd.of());
    }

    @Test
    void 终态用量投影message_usage且零用量不发() {
        AgentEventMapper mapper = new AgentEventMapper();
        AssistantMessage withUsage =
                AssistantMessage.builder()
                        .textContent("好了")
                        .usage(
                                ChatUsage.builder()
                                        .inputTokens(100)
                                        .outputTokens(50)
                                        .cachedTokens(20)
                                        .build())
                        .build();

        // AgentScope totalTokens 口径 = input+output，不含 cached
        assertThat(mapper.map(new AgentResultEvent(withUsage)))
                .containsExactly(ChatDelta.MessageUsage.of(150L, null));

        // 无用量且正常收尾：不发 message_usage，免得前端把 undefined 当 0
        assertThat(mapper.map(new AgentResultEvent(AssistantMessage.builder().textContent("好了").build())))
                .isEmpty();
        assertThat(mapper.map(new AgentResultEvent(null))).isEmpty();
    }

    @Test
    void 非正常终因落stopReason且工具挂起显式报错() {
        AgentEventMapper mapper = new AgentEventMapper();

        assertThat(mapper.map(new AgentResultEvent(resultWith(GenerateReason.PERMISSION_ASKING))))
                .containsExactly(ChatDelta.MessageUsage.of(null, "permission_asking"));
        // 外部执行工具的 suspended 结果块当前无应答通道（bash 危险命令已迁框架 Permission 体系，M1-8）
        assertThat(mapper.map(new AgentResultEvent(resultWith(GenerateReason.TOOL_SUSPENDED))))
                .containsExactly(
                        ChatDelta.MessageUsage.of(null, "tool_suspended"),
                        ChatDelta.Error.of("工具已挂起等待外部执行，当前版本尚未接通该应答通道（M2）"));
    }

    @Test
    void 迭代触顶与全部拒绝投影为error() {
        AgentEventMapper mapper = new AgentEventMapper();

        assertThat(mapper.map(new ExceedMaxItersEvent("r1", 20, 20)))
                .containsExactly(ChatDelta.Error.of("已达最大迭代轮数 20，本轮中止"));
        assertThat(mapper.map(
                        new AllToolsDeniedEvent(
                                List.of(
                                        new ToolUseBlock("tc1", "bash", Map.of()),
                                        new ToolUseBlock("tc2", "write_file", Map.of())))))
                .containsExactly(ChatDelta.Error.of("工具调用全部被拒绝：bash、write_file"));
    }

    private static AssistantMessage resultWith(GenerateReason reason) {
        return AssistantMessage.builder().textContent("x").generateReason(reason).build();
    }
}
