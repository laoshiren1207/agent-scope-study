package com.laoshiren.agent.rag;

import com.laoshiren.agent.rag.result.KnowledgeDocument;
import com.laoshiren.agent.rag.result.SearchHit;
import com.laoshiren.agent.rag.result.VectorDocument;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.embedding.openai.OpenAITextEmbedding;
import io.agentscope.extensions.model.openai.OpenAIChatModel;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

public class BaseRagExample {

    public static double[] embed(OpenAITextEmbedding model, String text) {
        double[] vector = model
                .embed(TextBlock.builder().text(text).build())
                .block();
        if (vector == null || vector.length == 0) {
            throw new IllegalStateException("没有获取到文本向量");
        }
        return vector;
    }


    public static double cosineSimilarity(double[] a, double[] b) {
        if (a.length == 0 || a.length != b.length) {
            throw new IllegalArgumentException("向量必须非空且维度一致");
        }

        double dotProduct = 0;
        double squaredNormA = 0;
        double squaredNormB = 0;

        for (int i = 0; i < a.length; i++) {
            dotProduct += a[i] * b[i];
            squaredNormA += a[i] * a[i];
            squaredNormB += b[i] * b[i];
        }

        if (squaredNormA == 0 || squaredNormB == 0) {
            throw new IllegalArgumentException("零向量无法计算余弦相似度");
        }

        return dotProduct / (Math.sqrt(squaredNormA) * Math.sqrt(squaredNormB));
    }

    public static List<SearchHit> retrieve(List<VectorDocument> index, double[] questionVector, int topK) {

        return index.stream()
                .map(item -> new SearchHit(item.document(), cosineSimilarity(questionVector, item.vector())))
                .sorted(Comparator.comparingDouble(SearchHit::score).reversed())
                .limit(topK)
                .toList();
    }


    public static OpenAIChatModel chatModel() {
        return OpenAIChatModel.builder()
                .baseUrl("http://192.168.31.6:32354/v1")
                .modelName("qwen3.8-27b")
                .apiKey("")
                .stream(true)
                .build();
    }

    public static OpenAITextEmbedding embeddingModel() {
        return OpenAITextEmbedding.builder()
                .baseUrl("http://192.168.31.67:30005/v1")
                .modelName("Qwen3.5")
                .apiKey("EMPTY")
                .dimensions(4096)
                .build();
    }

    public static String buildPrompt(String question, List<KnowledgeDocument> documents) {

        String reference = documents.stream()
                .map(document -> "[%s]\n%s".formatted(
                        document.id(), document.content()))
                .collect(Collectors.joining("\n\n"));

        return """
                参考资料：
                %s
                
                用户问题：
                %s
                """.formatted(reference, question);
    }
}
