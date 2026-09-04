package com.agent.timo.chat;

import io.agentscope.core.message.Msg;
import java.util.List;

/**
 * 会话持久化的窄端口（M1-11）：{@link AgentSession} 只需要「run 收尾时增量落盘」与
 * 「全量重建时读历史」两件事，不感知 MyBatis-Plus / 事务细节——生产实现
 * {@code ChatPersistenceService}，测试可用脚本化 fake。
 */
public interface ChatPersistence {

    /**
     * 增量落盘结果。
     *
     * @param cursor 落盘后的 Msg 游标；会话行已删（FK 级联清理）返回 -1，调用方放弃
     * @param title 本次落盘回填的会话标题；未回填为 null
     */
    record SyncOutcome(int cursor, String title) {
    }

    /**
     * 把框架上下文快照中游标之后的部分投影成 entries 追加落盘（同事务推进游标/接链/回填标题）。
     * 幂等语义：失败不推进游标，下次重试同一段。
     */
    SyncOutcome persistNewTurns(String sessionId, List<Msg> context);

    /** 会话历史（前端 AgentMessage 形状、落盘顺序），供 messages_loaded 全量重建。 */
    List<Object> loadHistory(String sessionId);
}
