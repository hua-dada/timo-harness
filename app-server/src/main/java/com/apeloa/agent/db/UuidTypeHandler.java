package com.apeloa.agent.db;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedTypes;

/**
 * {@code java.util.UUID} ↔ PG uuid 列。MyBatis 无内置 UUID handler；写入走 setString，
 * 依赖连接串 {@code stringtype=unspecified}（见 application.yml），PG 会把无类型字面量
 * 收窄到 uuid——与实体 payload 直写 jsonb 同一机制。
 */
@MappedTypes(UUID.class)
public class UuidTypeHandler extends BaseTypeHandler<UUID> {

    @Override
    public void setNonNullParameter(
            PreparedStatement ps, int i, UUID parameter, JdbcType jdbcType) throws SQLException {
        ps.setString(i, parameter.toString());
    }

    @Override
    public UUID getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return parse(rs.getString(columnName));
    }

    @Override
    public UUID getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return parse(rs.getString(columnIndex));
    }

    @Override
    public UUID getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return parse(cs.getString(columnIndex));
    }

    private static UUID parse(String value) {
        return value == null ? null : UUID.fromString(value);
    }
}
