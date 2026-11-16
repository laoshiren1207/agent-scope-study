package com.laoshiren.agent.persistent;

import com.laoshiren.agent.persistent.api.ChunkRequests;
import com.laoshiren.agent.persistent.mapper.KbChunkMapper;
import com.laoshiren.agent.persistent.embedding.VllmTextEmbedding;
import io.agentscope.core.embedding.EmbeddingModel;
import jakarta.validation.Validator;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"spring.sql.init.mode=never",
                "spring.datasource.url=jdbc:postgresql://localhost:5432/unused_test_database",
                "rag.embedding.api-key=EMPTY", "rag.chat.api-key=EMPTY"})
class MapperConfigurationTest {
    @Autowired SqlSessionFactory sessions;
    @Autowired Validator validator;
    @Autowired EmbeddingModel embeddingModel;

    @Test
    void wiresVllmEmbeddingAdapter() {
        assertThat(embeddingModel).isInstanceOf(VllmTextEmbedding.class);
    }

    @Test
    void loadsMybatisPlusAndBindsScopedVectorQueryWithoutStringSubstitution() {
        var config = sessions.getConfiguration();
        assertThat(config.hasStatement(KbChunkMapper.class.getName() + ".insert")).isTrue();
        var query = config.getMappedStatement(KbChunkMapper.class.getName() + ".search")
                .getBoundSql(Map.of("tenantId", 1L, "knowledgeBaseId", 2L,
                        "profile", "test-profile", "embedding", new float[]{1, 0},
                        "minScore", 0.4, "limit", 5));
        assertThat(query.getSql()).contains("tenant_id = ?", "knowledge_base_id = ?",
                "embedding_profile = ?", "status = 'ready'", "deleted_at IS NULL", "<=> ?")
                .doesNotContain("test-profile");
        assertThat(query.getParameterMappings()).hasSize(6);
    }

    @Test
    void validatesNestedChunksAndSearchBounds() {
        assertThat(validator.validate(new ChunkRequests.Ingest(1L, 2L, 3L, 1, null,
                List.of(new ChunkRequests.Chunk(null, " ", null, null, null)))))
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains("chunks[0].content");
        assertThat(validator.validate(new ChunkRequests.Search(null, 1L, "", 101, 1.1)))
                .hasSize(4);
    }

    @Test
    void springSqlInitializerCanSplitTriggerFunction() throws Exception {
        var connection = mock(java.sql.Connection.class);
        var statement = mock(java.sql.Statement.class);
        org.mockito.Mockito.when(connection.createStatement()).thenReturn(statement);
        org.mockito.Mockito.when(statement.getUpdateCount()).thenReturn(-1);
        ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/updated-at-trigger.sql"));
        var sql = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(statement, org.mockito.Mockito.times(3)).execute(sql.capture());
        assertThat(sql.getAllValues().get(0)).contains("RETURN NEW;").endsWith("'");
    }
}
