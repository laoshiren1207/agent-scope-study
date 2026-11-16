package com.laoshiren.agent.persistent.typehandler;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.postgresql.util.PGobject;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class TypeHandlersTest {
    @Test
    void bindsVectorAsPostgresVectorAndReadsFloatArray() throws Exception {
        PreparedStatement ps = mock(PreparedStatement.class);
        VectorTypeHandler handler = new VectorTypeHandler();
        handler.setNonNullParameter(ps, 1, new float[]{1, -0.5f, 0}, null);
        ArgumentCaptor<PGobject> value = ArgumentCaptor.forClass(PGobject.class);
        verify(ps).setObject(eq(1), value.capture());
        assertThat(value.getValue().getType()).isEqualTo("vector");
        assertThat(value.getValue().getValue()).isEqualTo("[1.0,-0.5,0.0]");
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("embedding")).thenReturn("[1,-0.5,0]");
        assertThat(handler.getNullableResult(rs, "embedding")).containsExactly(1, -0.5f, 0);
        assertThat(handler.getNullableResult(rs, "absent")).isNull();
    }

    @Test
    void jsonbPreservesNestedObjectsAndUnicode() throws Exception {
        PreparedStatement ps = mock(PreparedStatement.class);
        JsonbTypeHandler handler = new JsonbTypeHandler();
        Map<String, Object> input = Map.of("章节", Map.of("page", 2), "tags", java.util.List.of("退款"));
        handler.setNonNullParameter(ps, 1, input, null);
        ArgumentCaptor<PGobject> value = ArgumentCaptor.forClass(PGobject.class);
        verify(ps).setObject(eq(1), value.capture());
        assertThat(value.getValue().getType()).isEqualTo("jsonb");
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("metadata")).thenReturn(value.getValue().getValue());
        assertThat(handler.getNullableResult(rs, "metadata")).isEqualTo(input);
    }
}
