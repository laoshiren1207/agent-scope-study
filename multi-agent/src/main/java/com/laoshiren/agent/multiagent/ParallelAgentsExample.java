package com.laoshiren.agent.multiagent;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
public class ParallelAgentsExample {

    public static void main(String[] args) {
        OpenAIChatModel model = OpenAIChatModel.builder()
                .baseUrl("http://192.168.31.6:32354/v1")
                .modelName("qwen3.8-27b")
                .apiKey("")
                .stream(false)
                .build();

        ReActAgent order = ReActAgent.builder()
                .name("order")
                .model(model)
                .sysPrompt("""
                        你是售后分析员。
                        仅根据输入的订单查询结果（随机给出已发货，未发货） 如果已发货 补充说明是否签收。
                        输出示例：
                        订单xxx 已发货，已签收。
                        订单xxx 已发货，未签收。
                        订单xxx 未发货。
                        """)
                .build();

        ReActAgent analyst = ReActAgent.builder()
                .name("analyst")
                .model(model)
                .sysPrompt("""
                        你是售后政策员。
                        - 未发货订单可以申请直接退款。
                        - 已发货订单不能直接退款，需要签收后按照退货流程申请。
                        - 以上资料没有说明退款到账时间。。
                        """)
                .build();

        ReActAgent customerService = ReActAgent.builder()
                .name("customer-service")
                .model(model)
                .sysPrompt("""
                        你是售后客服。
                        根据用户原始问题和分析员提供的分析组织回复。
                        使用自然、简洁的中文，控制在三句话以内。
                        保留分析中的条件和不确定性，不添加新政策。
                        不声称已经执行退款或其他业务操作。
                        """)
                .build();

        ExecutorService pool = Executors.newFixedThreadPool(2);


        String  userQuestion = "我的订单是 A0001 我能退货吗";

        try (order; analyst; customerService) {
            CompletableFuture<String> orderTask = CompletableFuture.supplyAsync(
                    () -> ask(order, userQuestion),
                    pool);

            CompletableFuture<String> policyTask = CompletableFuture.supplyAsync(
                    () -> ask(analyst, "退货退款政策"),
                    pool);

            String replyInput = orderTask.thenCombine(policyTask, (orderInfo, policy) -> """
                    用户原始问题：%s
                    订单事实：%s
                    退款政策：%s
                    """.formatted(userQuestion, orderInfo, policy)).join();

            ask(customerService, replyInput);
        } finally {
            pool.shutdown();
        }


    }


    private static String ask(ReActAgent agent, String input) {

        Msg reply = agent.call(input).block(Duration.ofSeconds(120));

        if (reply == null || reply.getTextContent() == null || reply.getTextContent().isBlank()) {
            throw new IllegalStateException("Agent 没有返回有效文本");
        }
        log.info("{}: {}",agent.getName(), reply.getTextContent());
        return reply.getTextContent();
    }

}
