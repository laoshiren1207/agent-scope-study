package com.laoshiren.agent.persistent.service;

import com.laoshiren.agent.persistent.api.ChunkRequests.Search;
import com.laoshiren.agent.persistent.api.ChunkResponses.Hit;
import com.laoshiren.agent.persistent.config.RagProperties;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 使用真实 TEI 响应解析、分数过滤和回答拼装，仅替换检索、HTTP 传输及 Agent。 */
class RagRerankAnswerServiceTest {
    private KbChunkService chunks;
    private HttpClient client;
    private HttpResponse<String> response;
    private ReActAgent agent;
    private RagProperties properties;
    private RagRerankAnswerService service;
    private final Search question = new Search(1L, 2L, "未发货能退款吗？", 20, 0.0);

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() throws Exception {
        chunks = mock(KbChunkService.class);
        client = mock(HttpClient.class);
        response = mock(HttpResponse.class);
        agent = mock(ReActAgent.class);
        properties = new RagProperties();
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);
        when(response.statusCode()).thenReturn(200);
        var reranker = new TeiReranker("http://localhost:8001", Duration.ofSeconds(10), client);
        var answers = new RagAnswerService(chunks, mock(OpenAIChatModel.class), properties, agent);
        service = new RagRerankAnswerService(chunks, reranker, answers, properties);
    }

    @Test
    void passesOnlyRerankedAndFilteredReferencesToChatWithoutSearchingAgain() {
        Hit shipping = hit(1L, "发货后3天送达。", 0.99);
        Hit refund = hit(2L, "未发货可退款。", 0.65);
        Hit returns = hit(3L, "签收后七天内可退货。", 0.8);
        when(chunks.search(question)).thenReturn(List.of(shipping, refund, returns));
        when(response.body()).thenReturn("""
                [{"index":0,"score":0.1},{"index":2,"score":0.7},{"index":1,"score":0.95}]
                """);
        properties.getRerank().setTopK(2);
        when(agent.call(anyString(), any(RuntimeContext.class))).thenReturn(Mono.just(
                Msg.builder().name("assistant").role(MsgRole.ASSISTANT)
                        .textContent("未发货可以退款。[chunk:2]").build()));

        var result = service.ask(question);
        assertThat(result.answer()).isEqualTo("未发货可以退款。[chunk:2]");
        assertThat(result.references()).extracting(item -> item.hit().id()).containsExactly(2L, 3L);
        assertThat(result.references()).extracting(item -> item.rerankScore()).containsExactly(0.95, 0.7);
        assertThat(result.references().get(0).hit().score()).isEqualTo(0.65);
        var prompt = ArgumentCaptor.forClass(String.class);
        verify(agent).call(prompt.capture(), any(RuntimeContext.class));
        assertThat(prompt.getValue()).contains("[chunk:2]", refund.content(), "[chunk:3]", returns.content())
                .doesNotContain(shipping.content());
        assertThat(prompt.getValue().indexOf("[chunk:2]")).isLessThan(prompt.getValue().indexOf("[chunk:3]"));
        verify(chunks, times(1)).search(question);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void skipsRerankAndChatWhenDatabaseReturnsNoCandidates(boolean enable) {
        properties.getRerank().setEnable(enable);
        when(chunks.search(question)).thenReturn(List.of());
        var result = service.ask(question);
        assertThat(result.answer()).isEqualTo("现有资料未说明。");
        assertThat(result.references()).isEmpty();
        verifyNoInteractions(client, agent);
    }

    @Test
    void disabledRerankUsesAllCandidatesInRecallOrderWithoutHttpOrRerankFiltering() {
        Hit shipping = hit(1L, "发货后3天送达。", 0.8);
        Hit refund = hit(2L, "未发货可退款。", 0.65);
        when(chunks.search(question)).thenReturn(List.of(shipping, refund));
        properties.getRerank().setEnable(false);
        properties.getRerank().setTopK(1);
        properties.getRerank().setMinScore(0.99);
        when(agent.call(anyString(), any(RuntimeContext.class))).thenReturn(Mono.just(
                Msg.builder().name("assistant").role(MsgRole.ASSISTANT)
                        .textContent("未发货可以退款。[chunk:2]").build()));

        var result = service.ask(question);

        assertThat(result.answer()).isEqualTo("未发货可以退款。[chunk:2]");
        assertThat(result.references()).extracting(item -> item.hit()).containsExactly(shipping, refund);
        assertThat(result.references()).allSatisfy(item -> assertThat(item.rerankScore()).isNull());
        var prompt = ArgumentCaptor.forClass(String.class);
        verify(agent).call(prompt.capture(), any(RuntimeContext.class));
        assertThat(prompt.getValue()).contains(shipping.content(), refund.content());
        assertThat(prompt.getValue().indexOf("[chunk:1]")).isLessThan(prompt.getValue().indexOf("[chunk:2]"));
        verify(chunks, times(1)).search(question);
        verifyNoInteractions(client);
    }

    @Test
    void skipsChatWhenEveryCandidateIsBelowRerankThreshold() {
        when(chunks.search(question)).thenReturn(List.of(hit(1L, "低相关", 0.99)));
        when(response.body()).thenReturn("[{\"index\":0,\"score\":0.1}]");
        var result = service.ask(question);
        assertThat(result.answer()).isEqualTo("现有资料未说明。");
        assertThat(result.references()).isEmpty();
        verifyNoInteractions(agent);
    }

    @Test
    void reportsRerankFailureAndDoesNotFallBackToUnfilteredReferences() throws Exception {
        when(chunks.search(question)).thenReturn(List.of(hit(1L, "正文", 0.9)));
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new IOException("provider-secret"));
        assertThatThrownBy(() -> service.ask(question)).isInstanceOf(ModelCallException.class)
                .hasMessage("重排模型调用失败、超时或返回结果不符合预期");
        verifyNoInteractions(agent);
    }

    @Test
    void restoresInterruptFlagWhenRerankCallIsInterrupted() throws Exception {
        when(chunks.search(question)).thenReturn(List.of(hit(1L, "正文", 0.9)));
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new InterruptedException("cancelled"));
        try {
            assertThatThrownBy(() -> service.ask(question)).isInstanceOf(ModelCallException.class)
                    .hasMessage("重排模型调用被中断");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            verifyNoInteractions(agent);
        } finally {
            Thread.interrupted();
        }
    }

    private static Hit hit(long id, String content, double score) {
        return new Hit(id, 10L, 1, (int) id, null, content, "upload:policy.md", Map.of(), Map.of(), score);
    }
}
