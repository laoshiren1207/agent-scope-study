package com.laoshiren.agent.rag;

import io.agentscope.core.embedding.openai.OpenAITextEmbedding;

public class TextEmbeddingExample extends BaseRagExample {

    public static void main(String[] args) {
        // 1. 配置文本向量模型
        OpenAITextEmbedding embeddingModel = embeddingModel();

//        // 2. 准备需要向量化的文本
//        String text = "未发货订单可以申请退款，申请时需要提供订单号。";
//        TextBlock input = TextBlock.builder()
//                .text(text)
//                .build();
//
//        // 3. 发起请求，等待返回向量
//        double[] vector = embeddingModel.embed(input).block();
//        if (vector == null || vector.length == 0) {
//            throw new IllegalStateException("没有获取到文本向量");
//        }
//
//        // 4. 查看结果
//        System.out.println("原文：" + text);
//        System.out.println("向量维度：" + vector.length);

        // similar

        // 问题没有直接使用“退款”“未发货”等关键词
        String question = "我买的东西还没寄出，钱能退给我吗？";
//        String question = "订单寄出后要几天才能收到？";

        String refundDocument = "未发货订单可以申请退款，申请时需要提供订单号。";

        String shippingDocument = "订单发货后，通常在3天内送达。";

// 分别生成向量，共调用三次 Embedding 服务
        double[] questionVector = embed(embeddingModel, question);
        double[] refundVector = embed(embeddingModel, refundDocument);
        double[] shippingVector = embed(embeddingModel, shippingDocument);

// 问题向量可以重复用于比较，无需再次请求模型
        double refundScore =
                cosineSimilarity(questionVector, refundVector);

        double shippingScore =
                cosineSimilarity(questionVector, shippingVector);

        System.out.println("用户问题：" + question);
        System.out.printf("退款资料相似度：%.4f%n", refundScore);
        System.out.printf("物流资料相似度：%.4f%n", shippingScore);
    }






}
