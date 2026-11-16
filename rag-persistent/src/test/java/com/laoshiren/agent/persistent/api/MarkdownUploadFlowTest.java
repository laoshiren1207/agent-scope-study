package com.laoshiren.agent.persistent.api;

import com.laoshiren.agent.persistent.config.RagProperties;
import com.laoshiren.agent.persistent.entity.KbChunk;
import com.laoshiren.agent.persistent.mapper.KbChunkMapper;
import com.laoshiren.agent.persistent.service.KbChunkService;
import com.laoshiren.agent.persistent.service.MarkdownChunker;
import com.laoshiren.agent.persistent.service.MarkdownUploadService;
import com.laoshiren.agent.persistent.service.RagRerankAnswerService;
import io.agentscope.core.embedding.EmbeddingModel;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 使用真实 Controller、分片器和 Service，仅替换数据库及外部模型，验证完整上传调用链。 */
class MarkdownUploadFlowTest {
    private MockMvc http;
    private KbChunkMapper mapper;
    private EmbeddingModel model;
    private PlatformTransactionManager transactions;

    @BeforeEach
    void setup() {
        mapper = mock(KbChunkMapper.class);
        model = mock(EmbeddingModel.class);
        transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        AtomicLong sequence = new AtomicLong();
        when(mapper.insert(any(KbChunk.class))).thenAnswer(call -> {
            call.getArgument(0, KbChunk.class).setId(sequence.incrementAndGet());
            return 1;
        });
        when(mapper.markReady(anyLong(), any())).thenReturn(1);
        when(mapper.markFailed(anyLong(), anyString())).thenReturn(1);
        when(model.embed(any())).thenReturn(Mono.just(new double[]{1, 0, 0}));
        var properties = new RagProperties();
        properties.getEmbedding().setDimensions(3);
        properties.getEmbedding().setModel("test-model");
        properties.getEmbedding().setProfile("test-3-raw-v1");
        var chunks = new KbChunkService(mapper, model, properties, transactions);
        var uploads = new MarkdownUploadService(new MarkdownChunker(), chunks);
        http = MockMvcBuilders.standaloneSetup(new KbChunkController(chunks,
                        mock(RagRerankAnswerService.class), uploads))
                .setControllerAdvice(new ApiExceptionHandler()).build();
    }

    @Test
    void fileOnlyRequestSplitsEmbedsAndSavesEachChunkWithDefaultScope() throws Exception {
        var file = markdown("C:\\docs\\policy.MD", "# 售后\n\n## 退款\n未发货可退款。\n\n## 退货\n七天内退货。");
        http.perform(multipart("/api/kb/chunks/upload-markdown").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value("1"))
                .andExpect(jsonPath("$.knowledgeBaseId").value("1"))
                .andExpect(jsonPath("$.documentId").value(matchesPattern("[1-9][0-9]*")))
                .andExpect(jsonPath("$.documentVersion").value(1))
                .andExpect(jsonPath("$.filename").value("policy.MD"))
                .andExpect(jsonPath("$.chunkCount").value(2))
                .andExpect(jsonPath("$.chunks[0].id").value(1))
                .andExpect(jsonPath("$.chunks[0].status").value("ready"))
                .andExpect(jsonPath("$.chunks[1].chunkNo").value(1));

        ArgumentCaptor<KbChunk> rows = ArgumentCaptor.forClass(KbChunk.class);
        var order = inOrder(mapper, transactions, model);
        order.verify(mapper, times(2)).insert(rows.capture());
        order.verify(transactions).commit(any());
        order.verify(model).embed(any());
        order.verify(mapper).markReady(eq(1L), eq(new float[]{1, 0, 0}));
        order.verify(model).embed(any());
        order.verify(mapper).markReady(eq(2L), eq(new float[]{1, 0, 0}));
        assertThat(rows.getAllValues()).allSatisfy(row -> {
            assertThat(row.getTenantId()).isEqualTo(1L);
            assertThat(row.getKnowledgeBaseId()).isEqualTo(1L);
            assertThat(row.getStatus()).isEqualTo("pending");
            assertThat(row.getSourceUri()).isEqualTo("upload:policy.MD");
            assertThat(row.getContentHash()).matches("[0-9a-f]{64}");
            assertThat(row.getEmbeddingProfile()).isEqualTo("test-3-raw-v1");
        });
        assertThat(rows.getAllValues().get(0).getDocumentId())
                .isEqualTo(rows.getAllValues().get(1).getDocumentId());
        assertThat(rows.getAllValues().get(1).getSourceLocator()).containsEntry("line_start", 6);
    }

    @Test
    void acceptsExplicitDocumentScopeForARevision() throws Exception {
        http.perform(multipart("/api/kb/chunks/upload-markdown")
                        .file(markdown("policy.md", "## 退款\n正文"))
                        .param("tenantId", "10").param("knowledgeBaseId", "20")
                        .param("documentId", "30").param("documentVersion", "2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.documentId").value("30"));
        var row = ArgumentCaptor.forClass(KbChunk.class);
        verify(mapper).insert(row.capture());
        assertThat(row.getValue().getTenantId()).isEqualTo(10L);
        assertThat(row.getValue().getKnowledgeBaseId()).isEqualTo(20L);
        assertThat(row.getValue().getDocumentVersion()).isEqualTo(2);
    }

    @Test
    void returnsPerChunkFailureWhenEmbeddingFails() throws Exception {
        when(model.embed(any())).thenReturn(Mono.error(new RuntimeException("unavailable")));
        http.perform(multipart("/api/kb/chunks/upload-markdown").file(markdown("test.md", "正文")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chunks[0].status").value("failed"));
        verify(mapper).markFailed(eq(1L), anyString());
        verify(mapper, never()).markReady(anyLong(), any());
    }

    @Test
    void rejectsInvalidFilesAndScopeBeforeCallingDatabaseOrModel() throws Exception {
        for (var file : new MockMultipartFile[]{markdown("test.txt", "正文"),
                markdown("empty.md", ""), markdown("blank.md", " \n"),
                markdown("binary.md", "正文\u0000"),
                new MockMultipartFile("file", "bad.md", "text/markdown", new byte[]{(byte) 0xC3}),
                markdown("too-many.md", "## 章节\n正文\n".repeat(201))}) {
            http.perform(multipart("/api/kb/chunks/upload-markdown").file(file))
                    .andExpect(status().isBadRequest());
        }
        http.perform(multipart("/api/kb/chunks/upload-markdown")
                        .file(markdown("test.md", "正文")).param("tenantId", "0"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(mapper, model, transactions);
    }

    @Test
    void rejectsMissingMultipleAndOversizedFiles() throws Exception {
        http.perform(multipart("/api/kb/chunks/upload-markdown")).andExpect(status().isBadRequest());
        http.perform(multipart("/api/kb/chunks/upload-markdown")
                        .file(markdown("a.md", "正文")).file(markdown("b.md", "正文")))
                .andExpect(status().isBadRequest());
        http.perform(multipart("/api/kb/chunks/upload-markdown")
                        .file(new MockMultipartFile("file", "large.md", "text/markdown",
                                new byte[1024 * 1024 + 1])))
                .andExpect(status().is(413));
        verifyNoInteractions(mapper, model, transactions);
    }

    private MockMultipartFile markdown(String filename, String content) {
        return new MockMultipartFile("file", filename, "text/markdown", content.getBytes(StandardCharsets.UTF_8));
    }
}
