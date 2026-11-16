package com.laoshiren.agent.persistent.service;

import com.laoshiren.agent.persistent.api.ChunkRequests;
import com.laoshiren.agent.persistent.config.RagProperties;
import com.laoshiren.agent.persistent.entity.KbChunk;
import com.laoshiren.agent.persistent.mapper.KbChunkMapper;
import io.agentscope.core.embedding.EmbeddingModel;
import io.agentscope.core.message.TextBlock;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class KbChunkServiceTest {
    private KbChunkMapper mapper;
    private EmbeddingModel model;
    private PlatformTransactionManager transactions;
    private KbChunkService service;

    @BeforeEach
    void setUp() {
        mapper = mock(KbChunkMapper.class);
        model = mock(EmbeddingModel.class);
        transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        AtomicLong ids = new AtomicLong();
        when(mapper.insert(any(KbChunk.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, KbChunk.class).setId(ids.incrementAndGet());
            return 1;
        });
        when(mapper.markReady(anyLong(), any())).thenReturn(1);
        when(mapper.markFailed(anyLong(), anyString())).thenReturn(1);
        RagProperties properties = new RagProperties();
        properties.getEmbedding().setDimensions(3);
        properties.getEmbedding().setModel("test-model");
        properties.getEmbedding().setProfile("test-3-raw-v1");
        service = new KbChunkService(mapper, model, properties, transactions);
    }

    @Test
    void commitsPendingRowsBeforeEmbeddingAndPreservesExactContent() throws Exception {
        when(model.embed(any())).thenReturn(Mono.just(new double[]{1, 0, 0}));
        String content = "  中文正文\n";
        var results = service.ingest(request(content));

        ArgumentCaptor<KbChunk> saved = ArgumentCaptor.forClass(KbChunk.class);
        var order = inOrder(mapper, transactions, model);
        order.verify(mapper).insert(saved.capture());
        order.verify(transactions).commit(any());
        order.verify(model).embed(any());
        order.verify(mapper).markReady(eq(1L), eq(new float[]{1, 0, 0}));
        KbChunk row = saved.getValue();
        assertThat(row.getContent()).isEqualTo(content);
        assertThat(row.getContentHash()).isEqualTo(HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8))));
        assertThat(row.getStatus()).isEqualTo("pending");
        assertThat(row.getChunkNo()).isZero();
        assertThat(row.getTokenCount()).isNull();
        assertThat(row.getSourceLocator()).isEmpty();
        assertThat(row.getMetadata()).isEmpty();
        assertThat(row.getEmbeddingProfile()).isEqualTo("test-3-raw-v1");
        assertThat(results.get(0).status()).isEqualTo("ready");
    }

    @Test
    void recordsFailureAndContinuesWithRemainingChunks() {
        when(model.embed(any())).thenReturn(Mono.error(new RuntimeException("secret-key")))
                .thenReturn(Mono.just(new double[]{1, 0, 0}));
        var result = service.ingest(new ChunkRequests.Ingest(1L, 2L, 3L, 1, null,
                List.of(chunk("失败片段"), chunk("成功片段"))));
        assertThat(result).extracting(item -> item.status()).containsExactly("failed", "ready");
        assertThat(result.get(0).errorMessage()).doesNotContain("secret-key");
        verify(mapper).markFailed(eq(1L), anyString());
        verify(mapper).markReady(eq(2L), any());
    }

    static List<double[]> invalidVectors() {
        return List.of(new double[]{1}, new double[]{0, 0, 0},
                new double[]{Double.NaN, 0, 0}, new double[]{Double.POSITIVE_INFINITY, 0, 0},
                new double[]{Double.MAX_VALUE, 0, 0}, new double[]{Double.MIN_VALUE, 0, 0});
    }

    @ParameterizedTest
    @MethodSource("invalidVectors")
    void rejectsInvalidVectorsBeforeDatabaseWrite(double[] vector) {
        when(model.embed(any())).thenReturn(Mono.just(vector));
        assertThat(service.ingest(request("正文")).get(0).status()).isEqualTo("failed");
        verify(mapper, never()).markReady(anyLong(), any());
    }

    @Test
    void rollsBackDuplicateVersionWithoutCallingModel() {
        when(mapper.insert(any(KbChunk.class))).thenThrow(new DuplicateKeyException("duplicate"));
        assertThatThrownBy(() -> service.ingest(request("正文")))
                .isInstanceOf(DuplicateKeyException.class);
        verify(transactions).rollback(any());
        verifyNoInteractions(model);
    }

    @Test
    void doesNotPublishAChunkArchivedDuringEmbedding() {
        when(model.embed(any())).thenReturn(Mono.just(new double[]{1, 0, 0}));
        when(mapper.markReady(anyLong(), any())).thenReturn(0);
        assertThatThrownBy(() -> service.ingest(request("正文")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("归档或删除");
        verify(mapper, never()).markFailed(anyLong(), anyString());
    }

    @Test
    void searchAlwaysSuppliesTenantKnowledgeBaseAndConfiguredProfile() {
        when(model.embed(any())).thenReturn(Mono.just(new double[]{1, 0, 0}));
        when(mapper.search(anyLong(), anyLong(), anyString(), any(), anyDouble(), anyInt()))
                .thenReturn(List.of());
        assertThat(service.search(new ChunkRequests.Search(10L, 20L, "问题", null, null))).isEmpty();
        verify(mapper).search(eq(10L), eq(20L), eq("test-3-raw-v1"),
                eq(new float[]{1, 0, 0}), eq(0.0), eq(5));
        ArgumentCaptor<TextBlock> query = ArgumentCaptor.forClass(TextBlock.class);
        verify(model).embed(query.capture());
        assertThat(query.getValue().getText()).isEqualTo("问题");
    }

    private ChunkRequests.Ingest request(String content) {
        return new ChunkRequests.Ingest(1L, 2L, 3L, 1, "file:///test.md", List.of(chunk(content)));
    }

    private ChunkRequests.Chunk chunk(String content) {
        return new ChunkRequests.Chunk(null, content, null, null, null);
    }
}
