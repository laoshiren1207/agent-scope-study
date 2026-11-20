package com.laoshiren.agent.multiagent;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.UUID;

@Slf4j
public class SequentialAgentsExample {

    public static void main(String[] args) {
        // 两个 Agent 复用同一个模型连接配置。
        OpenAIChatModel model = OpenAIChatModel.builder()
                .baseUrl("http://192.168.31.6:32354/v1")
                .modelName("qwen3.8-27b")
                .apiKey("")
                .stream(false)
                .build();

        String question = "我的订单 A001 已经发货了，还能直接退款吗？";
        String runId = UUID.randomUUID().toString();

        ReActAgent analyst = ReActAgent.builder()
                .name("analyst")
                .model(model)
                .sysPrompt("""
                        你是售后分析员。
                        仅根据输入的订单事实和政策分析问题。
                        输出：
                        1. 处理结论
                        2. 判断依据
                        3. 缺失的信息
                        资料不足时明确说明，不补充未提供的规则。
                        你的输出供客服使用，无需直接回复用户。
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

        try (analyst; customerService) {
            // 第一步：分析 Agent 根据明确提供的资料完成判断。
            String analysis = ask(analyst, """
                    用户问题：
                    %s
                    
                    订单事实：
                    订单 A001 已发货，尚未签收。
                    
                    教学演示政策：
                    - 未发货订单可以申请直接退款。
                    - 已发货订单不能直接退款，需要签收后按照退货流程申请。
                    - 以上资料没有说明退款到账时间。
                    """.formatted(question), runId + ":analysis");

            log.info("分析 Agent 输出： {}", analysis);

            // 第二步：把原问题和分析结果显式交给客服 Agent。
            String answer = ask(customerService, """
                    用户原始问题：
                    %s
                    
                    分析员结果：
                    %s
                    """.formatted(question, analysis), runId + ":reply");
            log.info("客服 Agent 输出： {}", answer);
        }
    }

    private static String ask(ReActAgent agent, String input, String sessionId) {

        RuntimeContext context = RuntimeContext.builder()
                .userId("multi-agent-student")
                .sessionId(sessionId)
                .build();

        Msg reply = agent.call(input, context).block(Duration.ofSeconds(120));

        if (reply == null || reply.getTextContent() == null || reply.getTextContent().isBlank()) {
            throw new IllegalStateException("Agent 没有返回有效文本");
        }

        return reply.getTextContent();
    }
}