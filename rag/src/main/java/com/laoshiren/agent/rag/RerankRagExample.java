package com.laoshiren.agent.rag;

import com.laoshiren.agent.rag.result.KnowledgeDocument;
import com.laoshiren.agent.rag.result.RerankHit;
import com.laoshiren.agent.rag.result.SearchHit;

import java.util.List;

public class RerankRagExample {

    public static void main(String[] args) throws Exception {
        TeiReranker reranker = new TeiReranker("http://192.168.31.67:8001");

        String question = "我买的东西还没寄出，钱能退给我吗？";

        // 本次只验证重排，向量分数暂时使用 0.0 占位
        List<SearchHit> candidates = List.of(
                new SearchHit(new KnowledgeDocument("shipping-policy", List.of(), "订单发货后，通常在3天内送达。"), 0.0),
                new SearchHit(new KnowledgeDocument("refund-policy", List.of(), "未发货订单可以申请退款，申请时需要提供订单号。"), 0.0),
                new SearchHit(new KnowledgeDocument("return-policy", List.of(), "已签收商品可在7天内申请退货，商品须保持完好。"), 0.0));

        List<RerankHit> hits = reranker.rerank(question, candidates, 2);


        System.out.println("用户问题：" + question);

        for (RerankHit hit : hits) {
            System.out.printf("[%s] 重排分数：%.4f 原文：%s%n", hit.document().id(), hit.rerankScore(), hit.document().content());
        }
    }
}