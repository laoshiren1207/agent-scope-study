package com.laoshiren.agent.rag.result;

public record RerankHit(KnowledgeDocument document, double vectorScore, double rerankScore
) {
}