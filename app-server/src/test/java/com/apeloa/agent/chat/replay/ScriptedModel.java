package com.apeloa.agent.chat.replay;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.util.JsonUtils;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import reactor.core.publisher.Flux;

/**
 * PoC-A 的测量仪：把「模型这次看到的完整 prompt」原样记下来，再按脚本回一轮。
 *
 * <p>为什么必须是它而不是真模型：重放一致性的判据是<b>模型可见上下文是否等同</b>
 * （ReActAgent 每轮把 {@code [system] + AgentState.context} 交给 {@code Model.stream}，
 * 见 ReActAgent#reasoning → prependSystemMsg）。真模型只能给出「回答像不像」的软证据，
 * 而这里能对 prompt 逐条逐块做等值断言——真模型端到端复核另做（见 poc profile 实证）。
 *
 * <p>单 chunk 即一轮：ToolCallsAccumulator 接受一次给全的块，finishReason 框架不消费
 * （是否续跑只看有没有累积到工具调用）。工具调用块必须用 {@link #toolCall} 构造，原因见其注释。
 */
final class ScriptedModel implements Model {

    /** 每次 stream 调用收到的 prompt（含 system），按调用顺序。 */
    private final List<List<Msg>> prompts = Collections.synchronizedList(new ArrayList<>());

    /** 待回的轮次；用尽则回一句兜底文本，避免测试挂死在无限 ReAct 循环上。 */
    private final Deque<List<ContentBlock>> script = new ArrayDeque<>();

    /**
     * 脚本里的工具调用块。<b>{@code content}（参数原始 JSON 串）必须与 {@code input} 成对给</b>：
     * 框架的参数校验读的是 {@code ToolUseBlock.getContent()}（ToolExecutor#executeCore →
     * ToolValidator.validateInput），只给 input map 时 ToolCallsAccumulator 会把 content 补成
     * {@code "{}"}，于是校验以「未找到必需属性」判失败、工具压根不执行；真模型侧同样是两者成对
     * 落块（ToolCallsAccumulator#build：input=解析后的 map，content=累积的原始 JSON）。
     */
    static ToolUseBlock toolCall(String id, String name, Map<String, Object> args) {
        return ToolUseBlock.builder()
                .id(id)
                .name(name)
                .input(args)
                .content(JsonUtils.getJsonCodec().toJson(args))
                .build();
    }

    void enqueue(ContentBlock... blocks) {
        script.add(List.of(blocks));
    }

    /** 第 n 次（1 起）调用时模型看到的 prompt。 */
    List<Msg> promptAt(int n) {
        return prompts.get(n - 1);
    }

    int callCount() {
        return prompts.size();
    }

    void reset() {
        prompts.clear();
        script.clear();
    }

    @Override
    public Flux<ChatResponse> stream(
            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        prompts.add(List.copyOf(messages));
        List<ContentBlock> blocks = script.poll();
        if (blocks == null) {
            blocks = List.of(TextBlock.builder().text("(脚本已用尽)").build());
        }
        return Flux.just(
                ChatResponse.builder()
                        .id(UUID.randomUUID().toString())
                        .content(blocks)
                        .usage(ChatUsage.builder().inputTokens(1).outputTokens(1).build())
                        .finishReason("stop")
                        .build());
    }

    @Override
    public String getModelName() {
        return "scripted-poc-a";
    }
}
