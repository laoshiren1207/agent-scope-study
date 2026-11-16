package com.laoshiren.agent.rag;

import com.laoshiren.agent.rag.result.KnowledgeDocument;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.extensions.model.openai.OpenAIChatModel;

import java.util.List;

public class KeywordRagExample extends BaseRagExample {

    private static final List<KnowledgeDocument> DOCUMENTS = List.of(
            new KnowledgeDocument("refund-policy", List.of("退款", "未发货"), "未发货订单可以申请退款，申请时需要提供订单号。"),

            new KnowledgeDocument("return-policy", List.of("退货", "签收"), "已签收商品可在7天内申请退货，商品须保持完好。"),

            new KnowledgeDocument("shipping-policy", List.of("物流", "配送", "送达"), "订单发货后，通常在3天内送达。")
    );


    public static void main(String[] args) {
        String question = "可以开发票吗？";

        // 1. 检索相关资料
        List<KnowledgeDocument> matches = retrieve(question);

        System.out.println("用户问题：" + question);
        System.out.println("命中资料：" + matches.stream().map(KnowledgeDocument::id).toList());

        if (matches.isEmpty()) {
            System.out.println("助手：未检索到相关资料，暂时无法回答。");
            return;
        }

        // 2. 将检索结果和问题组装成提示词
        String prompt = buildPrompt(question, matches);

        System.out.println("发送给模型的内容：\n" + prompt);

        OpenAIChatModel model = chatModel();

        RuntimeContext context = RuntimeContext.builder()
                .userId("alice")
                .sessionId("keyword-rag-demo")
                .build();

        // 3. 让模型根据检索结果生成回答
        try (ReActAgent agent = ReActAgent.builder()
                .name("rag-assistant")
                .sysPrompt("""
                        你是业务知识助手。
                        只能根据当前提供的参考资料回答。
                        回答中使用 [资料编号] 标明依据。
                        如果参考资料不足以回答问题，
                        请明确说明“现有资料未说明”，不要编造。
                        """)
                .model(model)
                .build()) {

            Msg reply = agent.call(prompt, context).block();
            System.out.println("助手：" + reply.getTextContent());
        }
    }

    private static List<KnowledgeDocument> retrieve(String question) {
        return DOCUMENTS.stream()
                .filter(document -> document.keywords().stream()
                        .anyMatch(question::contains))
                .limit(2)
                .toList();
    }


}
