package com.laoshiren.agent.persistent.embedding;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.embeddings.EmbeddingCreateParams;
import io.agentscope.core.embedding.EmbeddingModel;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import java.time.Duration;
import java.util.List;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 适配 vLLM 的 OpenAI 兼容 Embedding 接口，不在请求中指定 dimensions。
 *
 * <p>当前服务拒绝显式指定维度，但不指定时可返回模型原生的 2560 维向量。
 * 配置中的 dimensions 只表示应用期望的维度，返回值仍由 KbChunkService 校验。
 * 实现 AgentScope 的 EmbeddingModel，让文档入库和问题检索使用同一个模型。
 */
public class VllmTextEmbedding implements EmbeddingModel, AutoCloseable {
    private final OpenAIClient client;
    private final String modelName;
    private final int dimensions;

    /** 复用 SDK 客户端；请求超时使用 rag.embedding.timeout-seconds。 */
    public VllmTextEmbedding(String baseUrl, String apiKey, String modelName,
            int dimensions, Duration timeout) {
        this(OpenAIOkHttpClient.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey == null || apiKey.isBlank() ? "EMPTY" : apiKey)
                .timeout(timeout)
                .maxRetries(0)
                .build(), modelName, dimensions);
    }

    // 测试注入 SDK 客户端，验证实际序列化的 HTTP 请求，不依赖外部模型服务。
    VllmTextEmbedding(OpenAIClient client, String modelName, int dimensions) {
        this.client = client;
        this.modelName = modelName;
        this.dimensions = dimensions;
    }

    @Override
    public Mono<double[]> embed(ContentBlock block) {
        if (!(block instanceof TextBlock textBlock)
                || textBlock.getText() == null || textBlock.getText().isBlank()) {
            return Mono.error(new IllegalArgumentException("向量化只支持非空文本"));
        }
        return Mono.fromCallable(() -> {
            // 关键：不能调用 .dimensions(...)，让服务返回原生维度。
            var request = EmbeddingCreateParams.builder()
                    .model(modelName)
                    .inputOfArrayOfStrings(List.of(textBlock.getText()))
                    .encodingFormat(EmbeddingCreateParams.EncodingFormat.FLOAT)
                    .build();
            var data = client.embeddings().create(request).data();
            if (data.size() != 1 || data.get(0).index() != 0) {
                throw new IllegalStateException("模型未返回当前文本对应的唯一向量");
            }
            var values = data.get(0).embedding();
            if (values.isEmpty()) {
                throw new IllegalStateException("模型返回了空向量");
            }
            double[] vector = new double[values.size()];
            for (int i = 0; i < values.size(); i++) {
                vector[i] = values.get(i);
            }
            return vector;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public String getModelName() {
        return modelName;
    }

    /** 应用期望的返回维度，不作为模型请求参数。 */
    @Override
    public int getDimensions() {
        return dimensions;
    }

    /** Spring 容器销毁 Bean 时释放 SDK 的连接池和线程资源。 */
    @Override
    public void close() {
        client.close();
    }
}
