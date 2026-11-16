package com.laoshiren.agent.persistent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.laoshiren.agent.persistent.api.ChunkResponses.Hit;
import com.laoshiren.agent.persistent.api.ChunkResponses.RerankHit;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TeiRerankerTest {
    private HttpClient client;
    private HttpResponse<String> response;
    private TeiReranker reranker;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() throws Exception {
        client = mock(HttpClient.class);
        response = mock(HttpResponse.class);
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);
        when(response.statusCode()).thenReturn(200);
        reranker = new TeiReranker("http://localhost:8001/", Duration.ofSeconds(10), client);
    }

    @Test
    void sendsOrderedTextsAndMapsResponseIndicesBeforeSortingAndLimiting() throws Exception {
        Hit shipping = hit(1L, "发货后3天送达。", 0.95);
        Hit refund = hit(2L, "未发货可退款。", 0.6);
        when(response.body()).thenReturn("""
                [{"index":1,"score":0.9},{"index":0,"score":0.1}]
                """);
        var results = reranker.rerank("能退款吗？", List.of(shipping, refund), 1);
        assertThat(results).containsExactly(new RerankHit(refund, 0.9));
        assertThat(results.get(0).hit().score()).isEqualTo(0.6);

        var request = ArgumentCaptor.forClass(HttpRequest.class);
        verify(client).send(request.capture(), any(HttpResponse.BodyHandler.class));
        assertThat(request.getValue().uri().toString()).isEqualTo("http://localhost:8001/rerank");
        assertThat(request.getValue().timeout()).contains(Duration.ofSeconds(10));
        var json = new ObjectMapper().readTree(body(request.getValue()));
        assertThat(json.path("query").asText()).isEqualTo("能退款吗？");
        assertThat(json.path("texts").get(0).asText()).isEqualTo(shipping.content());
        assertThat(json.path("texts").get(1).asText()).isEqualTo(refund.content());
        assertThat(json.path("raw_scores").asBoolean()).isFalse();
        assertThat(json.path("return_text").asBoolean()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}", "[]", "not-json",
            "[{\"index\":0,\"score\":0.9},{\"index\":0,\"score\":0.8}]",
            "[{\"index\":2,\"score\":0.9},{\"index\":0,\"score\":0.8}]",
            "[{\"index\":\"1\",\"score\":0.9},{\"index\":0,\"score\":0.8}]",
            "[{\"index\":1,\"score\":\"0.9\"},{\"index\":0,\"score\":0.8}]"
    })
    void rejectsInvalidOrIncompleteResponses(String json) {
        when(response.body()).thenReturn(json);
        assertThatThrownBy(() -> reranker.rerank("退款", List.of(hit(1L, "a", 0.9), hit(2L, "b", 0.8)), 2))
                .isInstanceOf(IOException.class);
    }

    @Test
    void rejectsHttpErrorWithoutIncludingProviderResponse() {
        when(response.statusCode()).thenReturn(503);
        when(response.body()).thenReturn("provider-secret");
        assertThatThrownBy(() -> reranker.rerank("退款", List.of(hit(1L, "正文", 0.9)), 1))
                .isInstanceOf(IOException.class).hasMessage("TEI 重排失败，HTTP 503");
    }

    @Test
    void skipsHttpForEmptyCandidatesAndIncludesThresholdBoundary() throws Exception {
        assertThat(reranker.rerank("退款", List.of(), 3)).isEmpty();
        verifyNoInteractions(client);
        var low = new RerankHit(hit(1L, "低相关", 0.99), 0.49);
        var boundary = new RerankHit(hit(2L, "刚好达到阈值", 0.7), 0.5);
        var high = new RerankHit(hit(3L, "高相关", 0.6), 0.9);
        assertThat(reranker.minScore(List.of(low, boundary, high), 0.5)).containsExactly(high, boundary);
    }

    private static Hit hit(long id, String content, double score) {
        return new Hit(id, 10L, 1, (int) id, null, content,
                "upload:policy.md", Map.of(), Map.of(), score);
    }

    private static String body(HttpRequest request) {
        var bytes = new ByteArrayOutputStream();
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<ByteBuffer>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
            @Override
            public void onNext(ByteBuffer buffer) {
                byte[] part = new byte[buffer.remaining()];
                buffer.get(part);
                bytes.writeBytes(part);
            }
            @Override
            public void onError(Throwable error) { throw new AssertionError(error); }
            @Override
            public void onComplete() {}
        });
        return bytes.toString(StandardCharsets.UTF_8);
    }
}
