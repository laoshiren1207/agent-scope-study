package com.laoshiren.agent.typehandler;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.postgresql.util.PGobject;

/** 在 PostgreSQL JSONB 对象与 Map 之间转换。 */
public class JsonbTypeHandler extends BaseTypeHandler<Map<String, Object>> {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, Map<String, Object> parameter,
                                    JdbcType jdbcType) throws SQLException {
        try {
            PGobject value = new PGobject();
            value.setType("jsonb");
            value.setValue(JSON.writeValueAsString(parameter));
            ps.setObject(i, value);
        } catch (IOException exception) {
            throw new SQLException("JSONB 序列化失败", exception);
        }
    }

    @Override
    public Map<String, Object> getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return parse(rs.getString(columnName));
    }

    @Override
    public Map<String, Object> getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return parse(rs.getString(columnIndex));
    }

    @Override
    public Map<String, Object> getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return parse(cs.getString(columnIndex));
    }

    private Map<String, Object> parse(String value) throws SQLException {
        if (value == null) return null;
        try {
            return JSON.readValue(value, MAP);
        } catch (IOException exception) {
            throw new SQLException("JSONB 必须是对象", exception);
        }
    }
}
