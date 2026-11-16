package com.laoshiren.agent.quickstart;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;

public class StructuredOutputExample extends BaseExample {

    public static void main(String[] args) {
        RuntimeContext context = RuntimeContext.builder()
                .userId("alice")
                .sessionId("structured-output-demo")
                .build();

        String input = """
                请从下面的文本中提取订单信息：
                
                订单 B002 是李四下的，买了三个无线鼠标。
                
                字段含义：
                orderId：订单号
                productName：商品名称
                quantity：购买数量
                buyer：购买人
                
                只提取文本明确提供的信息。
                """;

        try (ReActAgent agent = reActAgent(openAIChatModel(), null, agentStateStore())) {

            // 指定本次回复的结构
            Msg reply = agent.call(input, OrderInfo.class, context).block();

            if (reply == null || !reply.hasStructuredData()) {
                throw new IllegalStateException("本次回复未包含结构化数据");
            }

            // 将结构化结果转换为 Java 对象
            OrderInfo orderInfo = reply.getStructuredData(OrderInfo.class);

            System.out.println("订单号：" + orderInfo.orderId);
            System.out.println("商品：" + orderInfo.productName);
            System.out.println("数量：" + orderInfo.quantity);
            System.out.println("购买人：" + orderInfo.buyer);
        }
    }


}
