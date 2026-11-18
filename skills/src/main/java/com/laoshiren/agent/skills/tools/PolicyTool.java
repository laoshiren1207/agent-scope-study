package com.laoshiren.agent.skills.tools;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.util.JsonUtils;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

@Slf4j
public class PolicyTool {

    private final long tenantId = 1;
    private final long knowledgeBaseId = 2;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @Tool(
            name = "search_policy",
            description = """
                    检索退款、退货等售后政策，返回相关政策原文及来源。
                    判断订单是否符合售后条件时使用。
                    查询内容应描述需要了解的政策，例如：未发货订单的退款条件。
                    """,
            readOnly = true
    )
    public String searchPolicy(
            @ToolParam(name = "query", description = "需要检索的政策问题") String query) throws IOException, InterruptedException {

        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("检索问题不能为空");
        }

        String requestBody = JsonUtils.getJsonCodec().toJson(
                Map.of(
                        "tenantId", tenantId,
                        "knowledgeBaseId", knowledgeBaseId,
                        "query", query,
                        "topK", 3
                ));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:8083/api/kb/chunks/search"))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new IOException("知识库检索失败，HTTP " + response.statusCode());
        }
        log.info("rag: {}", response.body());
        return response.body();
    }
}

