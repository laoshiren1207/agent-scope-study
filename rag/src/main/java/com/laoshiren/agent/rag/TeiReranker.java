package com.laoshiren.agent.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.laoshiren.agent.rag.result.RerankHit;
import com.laoshiren.agent.rag.result.SearchHit;

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

public class TeiReranker {

    private final URI endpoint;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // baseUrl 示例：http://192.168.31.67:8001
    public TeiReranker(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("baseUrl 不能为空");
        }

        this.endpoint = URI.create(baseUrl.strip().replaceAll("/+$", "") + "/rerank");

        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    public List<RerankHit> rerank(String question, List<SearchHit> candidates, int topK) throws IOException, InterruptedException {
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("问题不能为空");
        }
        if (topK <= 0) {
            throw new IllegalArgumentException("topK 必须大于 0");
        }

        // 固定本次候选顺序，保证响应 index 能正确映射
        List<SearchHit> inputs = List.copyOf(candidates);

        if (inputs.isEmpty()) {
            return List.of();
        }

        // 1. 按候选顺序提取原文
        List<String> texts = inputs.stream()
                .map(hit -> hit.document().content())
                .toList();

        Map<String, Object> requestBody = Map.of(
                "query", question, //
                "texts", texts, //
                "raw_scores", false, //
                "return_text", false //
        );

        // 2. 调用 TEI
        HttpRequest request = HttpRequest.newBuilder()
                .uri(endpoint)
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        objectMapper.writeValueAsString(requestBody),
                        StandardCharsets.UTF_8
                ))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        );

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("TEI 重排失败，HTTP " + response.statusCode() + "，响应：" + response.body());
        }

        // 3. 解析响应
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

            // 4. 用 index 找回候选原文和原来的向量分数
            SearchHit original = inputs.get(index);

            results.add(new RerankHit(original.document(), original.score(), score));
        }

        // 5. 按重排分数降序排列，保留前 topK 条
        return results.stream()
                .sorted(Comparator.comparingDouble(RerankHit::rerankScore).reversed())
                .limit(topK)
                .toList();
    }

    public List<RerankHit> minScore(List<RerankHit> hits, double minScore) {
        return hits.stream()
                .filter(hit -> hit.rerankScore() >= minScore)
                .sorted(Comparator.comparingDouble(
                        RerankHit::rerankScore
                ).reversed())
                .toList();
    }
}