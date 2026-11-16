package com.laoshiren.agent.persistent.api;

import com.laoshiren.agent.persistent.config.RagProperties;
import com.laoshiren.agent.persistent.mapper.KbChunkMapper;
import com.laoshiren.agent.persistent.service.KbChunkService;
import com.laoshiren.agent.persistent.service.MarkdownUploadService;
import com.laoshiren.agent.persistent.service.RagRerankAnswerService;
import io.agentscope.core.embedding.EmbeddingModel;
import io.agentscope.core.message.TextBlock;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 通过真实 Controller 和 Service，验证问题向量及检索条件一直传递到 Mapper。 */
class ChunkSearchFlowTest {
    private KbChunkMapper mapper;
    private EmbeddingModel model;
    private MockMvc http;

    @BeforeEach
    void setup() {
        mapper = mock(KbChunkMapper.class);
        model = mock(EmbeddingModel.class);
        var properties = new RagProperties();
        properties.getEmbedding().setDimensions(3);
        properties.getEmbedding().setProfile("test-3-raw-v1");
        var chunks = new KbChunkService(mapper, model, properties,
                mock(PlatformTransactionManager.class));
        http = MockMvcBuilders.standaloneSetup(new KbChunkController(chunks,
                        mock(RagRerankAnswerService.class), mock(MarkdownUploadService.class)))
                .setControllerAdvice(new ApiExceptionHandler()).build();
        when(model.embed(any())).thenReturn(Mono.just(new double[]{1, 0, 0}));
    }

    @Test
    void embedsQuestionAndReturnsMapperHitsWithRequestedScopeAndThreshold() throws Exception {
        when(mapper.search(eq(10L), eq(20L), eq("test-3-raw-v1"),
                eq(new float[]{1, 0, 0}), eq(0.6), eq(3)))
                .thenReturn(List.of(new ChunkResponses.Hit(101L, 30L, 1, 0,
                        "退款", "未发货可以退款。", "upload:policy.md",
                        Map.of("line_start", 1), Map.of("filename", "policy.md"), 0.9)));

        http.perform(post("/api/kb/chunks/search").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"tenantId":10,"knowledgeBaseId":20,"query":"未发货能退款吗？",
                                 "topK":3,"minScore":0.6}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].documentId").value(30))
                .andExpect(jsonPath("$[0].content").value("未发货可以退款。"))
                .andExpect(jsonPath("$[0].sourceUri").value("upload:policy.md"))
                .andExpect(jsonPath("$[0].sourceLocator.line_start").value(1))
                .andExpect(jsonPath("$[0].score").value(0.9));

        var question = ArgumentCaptor.forClass(TextBlock.class);
        var order = inOrder(model, mapper);
        order.verify(model).embed(question.capture());
        order.verify(mapper).search(eq(10L), eq(20L), eq("test-3-raw-v1"),
                eq(new float[]{1, 0, 0}), eq(0.6), eq(3));
        assertThat(question.getValue().getText()).isEqualTo("未发货能退款吗？");
        verifyNoMoreInteractions(mapper);
    }

    @Test
    void usesDefaultTopKAndScoreWhenOptionalFieldsAreOmitted() throws Exception {
        when(mapper.search(anyLong(), anyLong(), anyString(), any(), anyDouble(), anyInt()))
                .thenReturn(List.of());
        http.perform(post("/api/kb/chunks/search").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"tenantId":1,"knowledgeBaseId":1,"query":"退款"}
                                """))
                .andExpect(status().isOk()).andExpect(content().json("[]"));
        verify(mapper).search(eq(1L), eq(1L), eq("test-3-raw-v1"),
                eq(new float[]{1, 0, 0}), eq(0.0), eq(5));
    }

    @Test
    void returnsModelFailureWithoutQueryingDatabase() throws Exception {
        when(model.embed(any())).thenReturn(Mono.error(new RuntimeException("provider-secret")));
        http.perform(post("/api/kb/chunks/search").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"tenantId":1,"knowledgeBaseId":1,"query":"退款"}
                                """))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.detail").value(
                        "向量化失败：模型调用超时、不可用或返回的向量不符合配置"));
        verifyNoInteractions(mapper);
    }
}
