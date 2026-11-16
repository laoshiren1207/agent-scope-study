package com.laoshiren.agent.persistent.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;

public final class ChunkRequests {
    private ChunkRequests() {}

    public record Ingest(
            @NotNull @Positive Long tenantId,
            @NotNull @Positive Long knowledgeBaseId,
            @NotNull @Positive Long documentId,
            @NotNull @Min(1) Integer documentVersion,
            String sourceUri,
            @NotEmpty @Size(max = 200) List<@NotNull @Valid Chunk> chunks) {}

    // chunkNo 由列表位置生成，从 0 开始；tokenCount 未使用模型 tokenizer 时留空。
    public record Chunk(
            String title,
            @NotBlank @Size(max = 100000) String content,
            @Positive Integer tokenCount,
            Map<String, Object> sourceLocator,
            Map<String, Object> metadata) {}

    /**
     * 知识库检索请求。profile 使用服务端配置，调用方只需指定检索范围和问题。
     *
     * @param tenantId 租户 ID，须与上传文档时一致
     * @param knowledgeBaseId 知识库 ID，须与上传文档时一致
     * @param query 用户输入的问题，最多 8000 个字符
     * @param topK 最多返回多少个片段；不传时为 5，允许 1 到 100
     * @param minScore 最低余弦相似度；不传时为 0，允许 -1 到 1
     */
    public record Search(
            @NotNull @Positive Long tenantId,
            @NotNull @Positive Long knowledgeBaseId,
            @NotBlank @Size(max = 8000) String query,
            @Min(1) @Max(100) Integer topK,
            @DecimalMin("-1.0") @DecimalMax("1.0") Double minScore) {
        /** 将可选的 topK 转成 SQL LIMIT 使用的实际数量。 */
        public int limit() { return topK == null ? 5 : topK; }
        /** 将可选的 minScore 转成 SQL 过滤使用的实际阈值。 */
        public double threshold() { return minScore == null ? 0.0 : minScore; }
    }

    public record VersionScope(
            @NotNull @Positive Long tenantId,
            @NotNull @Positive Long knowledgeBaseId,
            @NotNull @Positive Long documentId,
            @NotNull @Min(1) Integer documentVersion) {}
}
