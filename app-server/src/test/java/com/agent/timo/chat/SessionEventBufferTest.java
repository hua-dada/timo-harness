package com.agent.timo.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.agent.timo.chat.ChatDelta.TextDelta;
import com.agent.timo.chat.SessionEventBuffer.Sequenced;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * M1-6：Last-Event-Id 断线续传缓冲语义（seq 分配 / 窗口内补发 / 超窗回退 / 淘汰最旧）。
 */
class SessionEventBufferTest {

    @Test
    void seq从1单调分配且快照有序() {
        SessionEventBuffer buffer = new SessionEventBuffer(10);

        assertThat(buffer.append(TextDelta.of("a")).seq()).isEqualTo(1);
        assertThat(buffer.append(TextDelta.of("b")).seq()).isEqualTo(2);

        assertThat(buffer.firstSeq()).isEqualTo(1);
        assertThat(buffer.lastSeq()).isEqualTo(2);
        assertThat(buffer.snapshot())
                .extracting(Sequenced::seq)
                .containsExactly(1L, 2L);
    }

    @Test
    void 窗口内按LastEventId增量补发() {
        SessionEventBuffer buffer = new SessionEventBuffer(10);
        for (int i = 1; i <= 5; i++) {
            buffer.append(TextDelta.of("t" + i));
        }

        Optional<List<Sequenced>> replay = buffer.replayAfter(2);

        assertThat(replay).isPresent();
        assertThat(replay.get()).extracting(Sequenced::seq).containsExactly(3L, 4L, 5L);
    }

    @Test
    void LastEventId等于最新seq时补发为空直接挂实时流() {
        SessionEventBuffer buffer = new SessionEventBuffer(10);
        buffer.append(TextDelta.of("a"));
        buffer.append(TextDelta.of("b"));

        Optional<List<Sequenced>> replay = buffer.replayAfter(2);

        assertThat(replay).isPresent();
        assertThat(replay.get()).isEmpty();
    }

    @Test
    void 淘汰最旧后firstSeq前移且超窗回退空() {
        SessionEventBuffer buffer = new SessionEventBuffer(3);
        for (int i = 1; i <= 6; i++) {
            buffer.append(TextDelta.of("t" + i));
        }

        assertThat(buffer.firstSeq()).isEqualTo(4);
        assertThat(buffer.lastSeq()).isEqualTo(6);
        // 事件 1-3 已被淘汰：Last-Event-Id=2 早于 firstSeq-1=3 → 全量重建
        assertThat(buffer.replayAfter(2)).isEmpty();
        // 恰在窗口边界（firstSeq-1=3）仍可补发
        Optional<List<Sequenced>> boundary = buffer.replayAfter(3);
        assertThat(boundary).isPresent();
        assertThat(boundary.get()).extracting(Sequenced::seq).containsExactly(4L, 5L, 6L);
    }

    @Test
    void 空缓冲时lastSeq为0且任意LastEventId只回空补发() {
        SessionEventBuffer buffer = new SessionEventBuffer(10);

        assertThat(buffer.lastSeq()).isZero();
        Optional<List<Sequenced>> replay = buffer.replayAfter(0);
        assertThat(replay).isPresent();
        assertThat(replay.get()).isEmpty();
    }

    @Test
    void 容量必须为正() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new SessionEventBuffer(0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
