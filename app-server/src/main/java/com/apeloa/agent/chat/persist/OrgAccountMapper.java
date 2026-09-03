package com.apeloa.agent.chat.persist;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * {@code org_accounts} 通用 CRUD（M1-3 登录查询 / 改密 / 每请求账号复查）。
 * 无自定义 SQL：selectById / updateById 足够，密码哈希只经 AuthController 写入。
 */
@Mapper
public interface OrgAccountMapper extends BaseMapper<OrgAccountEntity> {
}
