package com.laoshiren.agent.persistent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.laoshiren.agent.persistent.api.ChunkResponses.Hit;
import com.laoshiren.agent.persistent.api.ChunkResponses.RerankHit;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 从 rag 模块的 TeiReranker 复制并适配，使用 TEI HTTP 接口对数据库召回的片段重排。
 * 保留 query/texts 请求格式，以及通过响应 index 找回原始候选的处理方式。
 */
public class TeiReranker {
    private final URI endpoint;
    private final HttpClient httpClient;
    private final Duration timeout;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public TeiReranker(String baseUrl) {
        this(baseUrl, Duration.ofSeconds(60));
    }

    /** baseUrl 为 TEI 服务根地址，类内部追加 /rerank。 */
    public TeiReranker(String baseUrl, Duration timeout) {
        this(baseUrl, timeout, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5)).build());
    }

    // 允许测试替换 HTTP 客户端，验证真实请求和响应解析，而不访问外部服务。
    TeiReranker(String baseUrl, Duration timeout, HttpClient httpClient) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("baseUrl 不能为空");
        }
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout 必须大于 0");
        }
        this.endpoint = URI.create(baseUrl.strip().replaceAll("/+$", "") + "/rerank");
        this.timeout = timeout;
        this.httpClient = httpClient;
    }

    /**
     * 对候选正文重新评分，按重排分数降序返回前 topK 条。
     *
     * @param question 原始问题，直接交给重排模型
     * @param candidates 数据库召回的片段，Hit.score 保留原始向量相似度
     * @param topK 重排后最多保留的片段数
     */
    public List<RerankHit> rerank(String question, List<Hit> candidates, int topK)
            throws IOException, InterruptedException {
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("问题不能为空");
        }
        if (topK <= 0) {
            throw new IllegalArgumentException("topK 必须大于 0");
        }

        // 1. 固定候选顺序，TEI 返回的 index 对应这里的列表位置。
        List<Hit> inputs = List.copyOf(candidates);
        if (inputs.isEmpty()) return List.of();
        List<String> texts = inputs.stream().map(Hit::content).toList();
        Map<String, Object> requestBody = Map.of(
                "query", question,
                "texts", texts,
                "raw_scores", false,
                "return_text", false);

        // 2. 沿用原类的 HTTP 调用方式，发送问题和全部候选正文。
        HttpRequest request = HttpRequest.newBuilder()
                .uri(endpoint).timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        objectMapper.writeValueAsString(requestBody), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("TEI 重排失败，HTTP " + response.statusCode());
        }

        // 3. 校验结果数量、索引和分数，拒绝错位、重复或不完整的响应。
        JsonNode rows = objectMapper.readTree(response.body());
        if (rows == null || !rows.isArray() || rows.size() != inputs.size()) {
            throw new IOException("TEI 返回的结果格式或数量不符合预期");
        }
        List<RerankHit> results = new ArrayList<>();
        boolean[] seen = new boolean[inputs.size()];
        for (JsonNode row : rows) {
            JsonNode indexNode = row.path("index");
            JsonNode scoreNode = row.path("score");
            if (!indexNode.isIntegralNumber() || !indexNode.canConvertToInt() || !scoreNode.isNumber()) {
                throw new IOException("TEI 返回的 index 或 score 类型错误");
            }
            int index = indexNode.intValue();
            double score = scoreNode.doubleValue();
            if (index < 0 || index >= inputs.size() || seen[index] || !Double.isFinite(score)) {
                throw new IOException("TEI 返回了无效或重复的结果");
            }
            seen[index] = true;

            // 4. 按 index 找回完整 Hit，保留 ID、原文、来源以及原始向量分数。
            results.add(new RerankHit(inputs.get(index), score));
        }

        // 5. 使用 TEI 重排分数排序，不再按向量分数排序。
        return results.stream()
                .sorted(Comparator.comparingDouble(RerankHit::rerankScore).reversed())
                .limit(topK).toList();
    }

    /** 仅保留重排分数达到阈值的片段，包含边界值，并保持分数降序。 */
    public List<RerankHit> minScore(List<RerankHit> hits, double minScore) {
        if (!Double.isFinite(minScore)) {
            throw new IllegalArgumentException("minScore 必须为有限数值");
        }
        return hits.stream()
                .filter(hit -> hit.rerankScore() >= minScore)
                .sorted(Comparator.comparingDouble(RerankHit::rerankScore).reversed())
                .toList();
    }
}
