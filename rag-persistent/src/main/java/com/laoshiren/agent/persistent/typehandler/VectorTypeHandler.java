package com.laoshiren.agent.persistent.typehandler;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.postgresql.util.PGobject;

/** 使用 PostgreSQL 原生 vector 类型绑定参数，无需逐连接注册 pgvector JDBC 类型。 */
public class VectorTypeHandler extends BaseTypeHandler<float[]> {
    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, float[] vector, JdbcType jdbcType)
            throws SQLException {
        if (vector.length == 0) {
            throw new SQLException("向量不能为空");
        }
        StringBuilder literal = new StringBuilder("[");
        for (int index = 0; index < vector.length; index++) {
            if (!Float.isFinite(vector[index])) {
                throw new SQLException("向量元素必须是有限浮点数");
            }
            if (index > 0) literal.append(',');
            literal.append(vector[index]);
        }
        PGobject value = new PGobject();
        value.setType("vector");
        value.setValue(literal.append(']').toString());
        ps.setObject(i, value);
    }

    @Override
    public float[] getNullableResult(ResultSet rs, String column) throws SQLException {
        return parse(rs.getString(column));
    }

    @Override
    public float[] getNullableResult(ResultSet rs, int column) throws SQLException {
        return parse(rs.getString(column));
    }

    @Override
    public float[] getNullableResult(CallableStatement cs, int column) throws SQLException {
        return parse(cs.getString(column));
    }

    private float[] parse(String literal) throws SQLException {
        if (literal == null) return null;
        if (!literal.startsWith("[") || !literal.endsWith("]")) {
            throw new SQLException("非法的 pgvector 数据");
        }
        String[] elements = literal.substring(1, literal.length() - 1).split(",");
        float[] vector = new float[elements.length];
        try {
            for (int i = 0; i < elements.length; i++) {
                vector[i] = Float.parseFloat(elements[i]);
            }
        } catch (NumberFormatException exception) {
            throw new SQLException("非法的 pgvector 数据", exception);
        }
        return vector;
    }
}
