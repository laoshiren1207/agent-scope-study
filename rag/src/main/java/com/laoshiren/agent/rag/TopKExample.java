package com.laoshiren.agent.rag;

import com.laoshiren.agent.rag.result.KnowledgeDocument;
import com.laoshiren.agent.rag.result.SearchHit;
import com.laoshiren.agent.rag.result.VectorDocument;
import io.agentscope.core.embedding.openai.OpenAITextEmbedding;

import java.util.List;

public class TopKExample extends BaseRagExample {


    public static void main(String[] args) {

        // 0. 配置文本向量模型
        OpenAITextEmbedding embeddingModel = embeddingModel();

        // 1. 准备资料；本次检索只使用 id 和 content
        List<KnowledgeDocument> documents = List.of(
                new KnowledgeDocument("refund-policy", List.of(), "未发货订单可以申请退款，申请时需要提供订单号。"),
                new KnowledgeDocument("return-policy", List.of(), "已签收商品可在7天内申请退货，商品须保持完好。"),
                new KnowledgeDocument("shipping-policy", List.of(), "订单发货后，通常在3天内送达。")
        );


        // 2. 提前生成文档向量，构建内存索引
        List<VectorDocument> index = documents.stream()
                .map(document -> new VectorDocument(
                        document,
                        embed(embeddingModel, document.content())
                ))
                .toList();

        // 3. 查询时，为每个问题生成一次向量
        List<String> questions = List.of("订单寄出后要几天才能收到？",
                "我买的东西还没寄出，钱能退给我吗？",
                "可以开发票吗");


        for (String question : questions) {
            double[] questionVector = embed(embeddingModel, question);

            List<SearchHit> hits = retrieve(index, questionVector, 2);

            System.out.println("\n用户问题：" + question);

            for (SearchHit hit : hits) {
                System.out.printf("[%s] 相似度：%.4f%n资料：%s%n", hit.document().id(), hit.score(), hit.document().content());
            }
        }

    }


}
