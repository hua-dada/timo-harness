package com.agent.timo.chat.persist;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** {@code sessions} 访问（CRUD 走 BaseMapper；条件查询见 ChatPersistenceService）。 */
@Mapper
public interface SessionMapper extends BaseMapper<SessionEntity> {
}
