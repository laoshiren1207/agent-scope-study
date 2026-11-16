package com.laoshiren.agent.persistent.api;

import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

import java.util.List;
import java.util.Map;

public final class ChunkResponses {
    private ChunkResponses() {
    }

    public record Ingested(Long id, int chunkNo, String status, String errorMessage) {
    }

    /**
     * Markdown 上传结果，返回文档归属和每个片段的入库状态，便于后续检索或版本更新。
     */
    public record UploadedDocument(

            @JsonSerialize(using = ToStringSerializer.class) Long tenantId,
            @JsonSerialize(using = ToStringSerializer.class) Long knowledgeBaseId,
            @JsonSerialize(using = ToStringSerializer.class) Long documentId,
            Integer documentVersion,
            String filename,
            int chunkCount,
            List<Ingested> chunks) {
    }

    /**
     * 一条检索命中结果：片段标识、原文、来源定位、业务属性及相似度。
     * score 是余弦相似度，越大越相近，不代表回答正确的概率；不返回数据库中的向量数组。
     */
    public record Hit(Long id, Long documentId, Integer documentVersion, Integer chunkNo,
                      String title, String content, String sourceUri,
                      Map<String, Object> sourceLocator, Map<String, Object> metadata,
                      double score) {
    }

    public record Answer(String answer, List<Hit> references) {
    }

    /** hit.score 保留向量相似度；rerankScore 是 TEI 分数，关闭重排时为 null。 */
    public record RerankHit(Hit hit, Double rerankScore) {
    }

    /** 问答结果；references 为最终提供的资料，启用重排时按重排分数排序，否则保留召回顺序。 */
    public record RerankAnswer(String answer, List<RerankHit> references) {
    }
}
