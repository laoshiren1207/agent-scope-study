package com.laoshiren.agent.persistent;

import com.laoshiren.agent.persistent.api.ChunkRequests;
import com.laoshiren.agent.persistent.entity.KbChunk;
import com.laoshiren.agent.persistent.mapper.KbChunkMapper;
import com.laoshiren.agent.persistent.service.KbChunkService;
import com.laoshiren.agent.persistent.service.MarkdownUploadService;
import io.agentscope.core.embedding.EmbeddingModel;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/** 显式设置 RAG_PG_TEST_URL 后运行，只能指向专用测试数据库。 */
@EnabledIfEnvironmentVariable(named = "RAG_PG_TEST_URL", matches = ".+")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"spring.sql.init.mode=always", "rag.embedding.profile=test-4096-raw-v1",
                "rag.embedding.dimensions=4096", "rag.embedding.api-key=EMPTY", "rag.chat.api-key=EMPTY"})
class PostgresIntegrationTest {
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("RAG_PG_TEST_URL"));
        registry.add("spring.datasource.username", () -> System.getenv()
                .getOrDefault("RAG_PG_TEST_USERNAME", "postgres"));
        registry.add("spring.datasource.password", () -> System.getenv()
                .getOrDefault("RAG_PG_TEST_PASSWORD", "postgres"));
    }

    @Autowired KbChunkService service;
    @Autowired MarkdownUploadService uploads;
    @Autowired KbChunkMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean EmbeddingModel embeddingModel;
    private long tenant;

    @BeforeEach
    void setUp() {
        tenant = ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE / 2);
        double[] vector = new double[4096];
        vector[0] = 1;
        when(embeddingModel.embed(any())).thenReturn(Mono.just(vector));
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM public.kb_chunk WHERE tenant_id IN (?, ?)", tenant, tenant + 1);
    }

    @Test
    void uploadsMarkdownAndPersistsSearchable4096DimensionalChunks() {
        var file = new org.springframework.mock.web.MockMultipartFile("file", "policy.md", "text/markdown",
                "# 售后\n\n## 退款\n未发货可退款。\n\n## 退货\n七天内退货。"
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var result = uploads.upload(file, tenant, 2L, 3L, 1);
        assertThat(result.chunkCount()).isEqualTo(2);
        assertThat(result.chunks()).allSatisfy(chunk -> assertThat(chunk.status()).isEqualTo("ready"));
        var row = mapper.selectById(result.chunks().get(0).id());
        assertThat(row.getEmbedding()).hasSize(4096);
        assertThat(row.getTitle()).isEqualTo("退款");
        assertThat(row.getSourceUri()).isEqualTo("upload:policy.md");
        assertThat(row.getSourceLocator()).containsEntry("section_path", List.of("售后", "退款"));
        assertThat(service.search(new ChunkRequests.Search(tenant, 2L, "退款", null, null))).hasSize(2);
    }

    @Test
    void persists4096DimensionsJsonbAndEnforcesSearchVisibility() {
        long id = ingest(1).get(0).id();
        KbChunk row = mapper.selectById(id);
        assertThat(row.getEmbedding()).hasSize(4096);
        assertThat(row.getSourceLocator()).containsEntry("page", 2);
        assertThat(row.getMetadata()).containsEntry("tags", List.of("退款"));
        assertThat(row.getEmbeddedAt()).isNotNull();
        assertThat(row.getUpdatedAt()).isAfterOrEqualTo(row.getCreatedAt());

        // 数据库查询必须排除其他租户、知识库、profile、状态及已删除记录。
        insertExcludedCopy(row, tenant + 1, 2L, "test-4096-raw-v1", "ready", false, 1);
        insertExcludedCopy(row, tenant, 99L, "test-4096-raw-v1", "ready", false, 2);
        insertExcludedCopy(row, tenant, 2L, "other-profile", "ready", false, 3);
        insertExcludedCopy(row, tenant, 2L, "test-4096-raw-v1", "pending", false, 4);
        insertExcludedCopy(row, tenant, 2L, "test-4096-raw-v1", "failed", false, 5);
        insertExcludedCopy(row, tenant, 2L, "test-4096-raw-v1", "archived", false, 6);
        insertExcludedCopy(row, tenant, 2L, "test-4096-raw-v1", "ready", true, 7);
        var hits = service.search(new ChunkRequests.Search(tenant, 2L, "退款", 10, 0.9));
        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).id()).isEqualTo(id);
        assertThat(hits.get(0).score()).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-6));
        assertThat(hits.get(0).metadata()).containsEntry("tags", List.of("退款"));
    }

    @Test
    void duplicateVersionDoesNotOverwriteExistingContent() {
        long id = ingest(1).get(0).id();
        assertThatThrownBy(() -> ingest(1)).isInstanceOf(DuplicateKeyException.class);
        assertThat(mapper.selectById(id).getContent()).isEqualTo("未发货可以退款。");
    }

    @Test
    void archivesAndSoftDeletesOnlyRequestedDocumentVersion() {
        ingest(1);
        long secondVersion = ingest(2).get(0).id();
        assertThat(service.archive(new ChunkRequests.VersionScope(tenant, 2L, 3L, 1))).isEqualTo(1);
        assertThat(service.search(new ChunkRequests.Search(tenant, 2L, "退款", null, null)))
                .extracting(hit -> hit.id()).containsExactly(secondVersion);
        assertThat(service.delete(new ChunkRequests.VersionScope(tenant, 2L, 3L, 2))).isEqualTo(1);
        assertThat(service.search(new ChunkRequests.Search(tenant, 2L, "退款", null, null))).isEmpty();
        assertThat(mapper.selectById(secondVersion).getDeletedAt()).isNotNull();
    }

    private List<com.laoshiren.agent.persistent.api.ChunkResponses.Ingested> ingest(int version) {
        return service.ingest(new ChunkRequests.Ingest(tenant, 2L, 3L, version, "file:///policy.md",
                List.of(new ChunkRequests.Chunk("退款", "未发货可以退款。", null,
                        Map.of("page", 2), Map.of("tags", List.of("退款"))))));
    }

    private void insertExcludedCopy(KbChunk original, long tenantId, long kbId, String profile,
                                     String status, boolean deleted, int chunkNo) {
        KbChunk copy = new KbChunk();
        org.springframework.beans.BeanUtils.copyProperties(original, copy);
        copy.setId(null);
        copy.setTenantId(tenantId);
        copy.setKnowledgeBaseId(kbId);
        copy.setEmbeddingProfile(profile);
        copy.setStatus(status);
        copy.setChunkNo(chunkNo);
        if (deleted) copy.setDeletedAt(java.time.OffsetDateTime.now());
        mapper.insert(copy);
    }
}
