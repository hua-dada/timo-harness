package com.apeloa.agent.chat.persist;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentscope.core.message.AssistantMessage;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultMessage;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.ChatUsage;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@link SessionEntryProjector} 投影矩阵（M1-11）：断言的是源前端 messages-history.ts 的
 * 收窄契约——字段名（thinking/arguments/toolCallId）、扁平 usage.totalTokens、toolResult
 * 独立成条。纯函数单测，不依赖 Spring/DB。
 */
class SessionEntryProjectorTest {

    @Test
    void 用户消息落字符串content() {
        Msg msg = UserMessage.builder().textContent("列一下文件").build();

        List<Map<String, Object>> out = SessionEntryProjector.project(msg);

        assertThat(out).hasSize(1);
        assertThat(out.get(0)).containsEntry("role", "user").containsEntry("content", "列一下文件");
    }

    @Test
    void assistant混合块保序且字段名对齐前端契约() {
        Msg msg =
                AssistantMessage.builder()
                        .content(
                                ThinkingBlock.builder().thinking("先想一下").build(),
                                text("我看下"),
                                new ToolUseBlock("tc1", "bash", Map.of("command", "ls")))
                        .usage(new ChatUsage(100, 50, 0, 0.1))
                        .build();

        List<Map<String, Object>> out = SessionEntryProjector.project(msg);

        assertThat(out).hasSize(1);
        Map<String, Object> am = out.get(0);
        assertThat(am.get("role")).isEqualTo("assistant");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> blocks = (List<Map<String, Object>>) am.get("content");
        assertThat(blocks).hasSize(3);
        // thinking 不是 text；toolCall 的参数叫 arguments 不叫 args
        assertThat(blocks.get(0)).containsEntry("type", "thinking").containsEntry("thinking", "先想一下");
        assertThat(blocks.get(1)).containsEntry("type", "text").containsEntry("text", "我看下");
        assertThat(blocks.get(2))
                .containsEntry("type", "toolCall")
                .containsEntry("id", "tc1")
                .containsEntry("name", "bash")
                .containsEntry("arguments", Map.of("command", "ls"));
        // 扁平 usage.totalTokens，不是嵌套 input/output
        assertThat(am.get("usage")).isEqualTo(Map.of("totalTokens", 150));
    }

    @Test
    void 无用量不落usage字段() {
        Msg msg = AssistantMessage.builder().content(text("好")).build();

        List<Map<String, Object>> out = SessionEntryProjector.project(msg);

        assertThat(out.get(0)).doesNotContainKey("usage").doesNotContainKey("stopReason");
    }

    @Test
    void base64图片块映射为前端image形状() {
        Base64Source source = new Base64Source("image/png", "aGVsbG8=");
        Msg msg =
                UserMessage.builder()
                        .content(text("看这张"), new io.agentscope.core.message.ImageBlock(source))
                        .build();

        List<Map<String, Object>> out = SessionEntryProjector.project(msg);

        // 带图消息 content 保留块数组（前端 imagesOf 从块里取图）；纯文本才是字符串。
        assertThat(out.get(0).get("content")).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> blocks = (List<Map<String, Object>>) out.get(0).get("content");
        assertThat(blocks.get(0)).containsEntry("type", "text").containsEntry("text", "看这张");
        assertThat(blocks.get(1))
                .containsEntry("type", "image")
                .containsEntry("data", "aGVsbG8=")
                .containsEntry("mimeType", "image/png");
    }

    @Test
    void 工具结果一转多且isError口径与实时一致() {
        Msg msg =
                ToolResultMessage.builder()
                        .content(
                                ToolResultBlock.of("tc1", "bash", text("a.txt\nb.txt")),
                                new ToolResultBlock(
                                        "tc2",
                                        "write_file",
                                        List.of(text("已写入"))))
                        .build();

        List<Map<String, Object>> out = SessionEntryProjector.project(msg);

        assertThat(out).hasSize(2);
        assertThat(out.get(0))
                .containsEntry("role", "toolResult")
                .containsEntry("toolCallId", "tc1")
                .containsEntry("isError", false)
                .containsEntry("content", "a.txt\nb.txt")
                .containsEntry("details", null);
        assertThat(out.get(1)).containsEntry("toolCallId", "tc2").containsEntry("content", "已写入");
    }

    @Test
    void 系统与合成消息不投影() {
        Msg synthetic =
                Msg.builder()
                        .role(io.agentscope.core.message.MsgRole.USER)
                        .textContent("提醒")
                        .metadata(Map.of(Msg.METADATA_SYNTHETIC, true))
                        .build();
        Msg system =
                Msg.builder()
                        .role(io.agentscope.core.message.MsgRole.SYSTEM)
                        .textContent("系统提示")
                        .build();

        assertThat(SessionEntryProjector.project(synthetic)).isEmpty();
        assertThat(SessionEntryProjector.project(system)).isEmpty();
    }

    @Test
    void 标题剥markdown归一空白并40字截断() {
        String long40 =
                "a".repeat(41); // 41 字触发截断
        Msg msg =
                UserMessage.builder()
                        .textContent("  请**帮**我\n\n看看 `ls` 的结果  ")
                        .build();
        Msg longMsg = UserMessage.builder().textContent(long40).build();
        Msg empty = UserMessage.builder().textContent("   ").build();

        assertThat(SessionEntryProjector.titleOf(msg)).isEqualTo("请帮我 看看 ls 的结果");
        assertThat(SessionEntryProjector.titleOf(longMsg))
                .isEqualTo("a".repeat(40) + "...");
        assertThat(SessionEntryProjector.titleOf(empty)).isNull();
        assertThat(SessionEntryProjector.titleOf(null)).isNull();
    }

    private static TextBlock text(String s) {
        return TextBlock.builder().text(s).build();
    }
}
