package com.apeloa.agent.chat.persist;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * {@code session_entries} 访问：只 insert + 顺序 select，append-only（ADR#2 本意）。
 * 排序按 {@code position}（同事务批量插入共享 created_at，时间戳不可作序）。
 */
@Mapper
public interface SessionEntryMapper extends BaseMapper<SessionEntryEntity> {

    /** 会话全部 entries，按落盘顺序（历史回放序）。 */
    @Select(
            """
            SELECT * FROM session_entries WHERE session_id = #{sessionId}
            ORDER BY position ASC
            """)
    java.util.List<SessionEntryEntity> listOrdered(@Param("sessionId") UUID sessionId);
}
