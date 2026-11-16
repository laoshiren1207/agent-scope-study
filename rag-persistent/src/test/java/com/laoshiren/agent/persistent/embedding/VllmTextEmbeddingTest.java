package com.laoshiren.agent.persistent.embedding;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.client.OpenAIClientImpl;
import com.openai.core.ClientOptions;
import com.openai.core.RequestOptions;
import com.openai.core.http.Headers;
import com.openai.core.http.HttpClient;
import com.openai.core.http.HttpRequest;
import com.openai.core.http.HttpResponse;
import io.agentscope.core.message.TextBlock;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 使用真实 SDK 序列化和解析，只替换网络传输，防止 dimensions 再次出现在请求中。 */
class VllmTextEmbeddingTest {
    private HttpClient transport;
    private HttpResponse response;
    private VllmTextEmbedding model;
    private final AtomicReference<HttpRequest> sentRequest = new AtomicReference<>();
    private final AtomicReference<String> sentBody = new AtomicReference<>();

    @BeforeEach
    void setup() {
        transport = mock(HttpClient.class);
        response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.headers()).thenReturn(Headers.builder().put("content-type", "application/json").build());
        when(transport.execute(any(HttpRequest.class), any(RequestOptions.class)))
                .thenAnswer(invocation -> {
                    HttpRequest request = invocation.getArgument(0);
                    sentRequest.set(request);
                    var bytes = new ByteArrayOutputStream();
                    request.body().writeTo(bytes);
                    sentBody.set(bytes.toString(StandardCharsets.UTF_8));
                    return response;
                });
        var client = new OpenAIClientImpl(ClientOptions.builder()
                .baseUrl("http://localhost:8000/v1")
                .apiKey("test-key")
                .httpClient(transport)
                .maxRetries(0)
                .build());
        model = new VllmTextEmbedding(client, "Qwen3-Embedding-4B", 2560);
    }

    @AfterEach
    void close() {
        model.close();
    }

    @Test
    void omitsDimensionsFromActualJsonAndReadsNative2560Vector() throws Exception {
        String values = "0.25,".repeat(2559) + "0.5";
        respond("[{\"object\":\"embedding\",\"index\":0,\"embedding\":[" + values + "]}]");

        double[] vector = model.embed(TextBlock.builder().text("coding plan 是什么").build())
                .block(Duration.ofSeconds(5));

        assertThat(vector).hasSize(2560);
        assertThat(vector[2559]).isEqualTo(0.5);
        assertThat(model.getDimensions()).isEqualTo(2560);
        assertThat(model.getModelName()).isEqualTo("Qwen3-Embedding-4B");
        assertThat(sentRequest.get().url()).isEqualTo("http://localhost:8000/v1/embeddings");
        assertThat(sentRequest.get().headers().values("Authorization")).contains("Bearer test-key");
        var json = new ObjectMapper().readTree(sentBody.get());
        assertThat(json.has("dimensions")).isFalse();
        assertThat(json.size()).isEqualTo(3);
        assertThat(json.path("model").asText()).isEqualTo("Qwen3-Embedding-4B");
        assertThat(json.path("input").get(0).asText()).isEqualTo("coding plan 是什么");
        assertThat(json.path("encoding_format").asText()).isEqualTo("float");
    }

    @ParameterizedTest
    @ValueSource(strings = {"[]",
            "[{\"object\":\"embedding\",\"index\":0,\"embedding\":[]}]",
            "[{\"object\":\"embedding\",\"index\":1,\"embedding\":[1]}]"})
    void rejectsMissingEmptyOrMismatchedVector(String data) {
        respond(data);
        assertThatThrownBy(() -> model.embed(TextBlock.builder().text("问题").build())
                .block(Duration.ofSeconds(5))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void propagatesProviderFailure() {
        when(response.statusCode()).thenReturn(400);
        when(response.body()).thenReturn(new ByteArrayInputStream(
                "{\"error\":{\"message\":\"invalid request\"}}".getBytes(StandardCharsets.UTF_8)));
        assertThatThrownBy(() -> model.embed(TextBlock.builder().text("问题").build())
                .block(Duration.ofSeconds(5))).isInstanceOf(RuntimeException.class);
    }

    @Test
    void rejectsBlankInputWithoutRequest() {
        assertThatThrownBy(() -> model.embed(TextBlock.builder().text(" ").build())
                .block(Duration.ofSeconds(5))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> model.embed(null).block(Duration.ofSeconds(5)))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(transport);
    }

    @Test
    void releasesHttpClientOnClose() {
        model.close();
        verify(transport).close();
    }

    private void respond(String data) {
        String body = "{\"object\":\"list\",\"model\":\"Qwen3-Embedding-4B\",\"data\":" + data
                + ",\"usage\":{\"prompt_tokens\":3,\"total_tokens\":3}}";
        when(response.body()).thenReturn(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
    }
}
