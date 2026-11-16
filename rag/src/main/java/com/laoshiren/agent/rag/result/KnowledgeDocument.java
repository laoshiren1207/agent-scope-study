package com.laoshiren.agent.rag.result;

import java.util.List;

public record KnowledgeDocument(String id, List<String> keywords, String content) {
}
