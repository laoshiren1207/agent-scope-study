package com.laoshiren.agent.rag.result;

public record VectorDocument(KnowledgeDocument document, double[] vector) {
}
