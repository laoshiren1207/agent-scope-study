package com.laoshiren.agent.persistent.api;

import com.laoshiren.agent.persistent.service.KbChunkService;
import com.laoshiren.agent.persistent.service.MarkdownUploadService;
import com.laoshiren.agent.persistent.service.RagRerankAnswerService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class KbChunkControllerTest {
    private KbChunkService chunks;
    private RagRerankAnswerService answers;
    private MockMvc http;

    @BeforeEach
    void setup() {
        chunks = mock(KbChunkService.class);
        answers = mock(RagRerankAnswerService.class);
        http = MockMvcBuilders.standaloneSetup(new KbChunkController(chunks, answers,
                        mock(MarkdownUploadService.class)))
                .setControllerAdvice(new ApiExceptionHandler()).build();
    }

    @Test
    void rejectsInvalidSearchWithoutCallingService() throws Exception {
        for (String body : List.of(
                """
                {"tenantId":1,"knowledgeBaseId":2,"query":" "}
                """,
                """
                {"knowledgeBaseId":2,"query":"退款"}
                """,
                """
                {"tenantId":1,"knowledgeBaseId":0,"query":"退款"}
                """,
                """
                {"tenantId":1,"knowledgeBaseId":2,"query":"退款","topK":101}
                """,
                """
                {"tenantId":1,"knowledgeBaseId":2,"query":"退款","minScore":1.1}
                """)) {
            http.perform(post("/api/kb/chunks/search").contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(chunks);
    }

    @Test
    void returnsJsonbMetadataInSearchResponse() throws Exception {
        when(chunks.search(any())).thenReturn(List.of(new ChunkResponses.Hit(1L, 3L, 1, 0,
                "退款", "正文", null, Map.of("page", 2), Map.of("tags", List.of("售后")), 0.9)));
        http.perform(post("/api/kb/chunks/search").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"tenantId":1,"knowledgeBaseId":2,"query":"退款"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].sourceLocator.page").value(2))
                .andExpect(jsonPath("$[0].metadata.tags[0]").value("售后"))
                .andExpect(jsonPath("$[0].score").value(0.9));
    }

    @Test
    void returnsEmptyListWhenNoChunkMatches() throws Exception {
        when(chunks.search(any())).thenReturn(List.of());
        http.perform(post("/api/kb/chunks/search").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"tenantId":1,"knowledgeBaseId":2,"query":"退款"}
                                """))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
    }

    @Test
    void askReturnsAnswerAndBothScoresFromRerankService() throws Exception {
        var hit = new ChunkResponses.Hit(1L, 3L, 1, 0, "退款", "未发货可退款。",
                "upload:policy.md", Map.of("page", 2), Map.of(), 0.7);
        when(answers.ask(any())).thenReturn(new ChunkResponses.RerankAnswer("可以申请退款。[chunk:1]",
                List.of(new ChunkResponses.RerankHit(hit, 0.95))));
        http.perform(post("/api/kb/chunks/ask").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"tenantId":1,"knowledgeBaseId":2,"query":"未发货能退款吗？","topK":20}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("可以申请退款。[chunk:1]"))
                .andExpect(jsonPath("$.references[0].hit.id").value(1))
                .andExpect(jsonPath("$.references[0].hit.score").value(0.7))
                .andExpect(jsonPath("$.references[0].rerankScore").value(0.95));
        verify(answers).ask(new ChunkRequests.Search(1L, 2L, "未发货能退款吗？", 20, null));
        verifyNoInteractions(chunks);
    }

    @Test
    void askReturnsNullRerankScoreWhenRerankIsDisabled() throws Exception {
        var hit = new ChunkResponses.Hit(1L, 3L, 1, 0, "退款", "未发货可退款。",
                "upload:policy.md", Map.of(), Map.of(), 0.7);
        when(answers.ask(any())).thenReturn(new ChunkResponses.RerankAnswer("可以申请退款。[chunk:1]",
                List.of(new ChunkResponses.RerankHit(hit, null))));
        http.perform(post("/api/kb/chunks/ask").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"tenantId":1,"knowledgeBaseId":2,"query":"未发货能退款吗？"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.references[0].hit.score").value(0.7))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"rerankScore\":null")));
    }

    @Test
    void rejectsBlankQuestionBeforeCallingRerankAnswerService() throws Exception {
        http.perform(post("/api/kb/chunks/ask").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"tenantId":1,"knowledgeBaseId":2,"query":" "}
                                """))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(answers, chunks);
    }
}
