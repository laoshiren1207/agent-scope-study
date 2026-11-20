package com.laoshiren.agent.multiagent;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;

@Slf4j
public class RoutingAgentsExample {

    enum Route { LOGISTICS, INVOICE, CLARIFY }

    public static void main(String[] args) {
        OpenAIChatModel model = OpenAIChatModel.builder()
                .baseUrl("http://192.168.31.6:32354/v1")
                .modelName("qwen3.8-27b")
                .apiKey("")
                .stream(false)
                .build();

        String question = args.length == 0 ? "订单 A001 的包裹到哪了？" : String.join(" ", args);

        try (ReActAgent logisticsAgent = ReActAgent.builder()
                .name("logistics").model(model)
                .sysPrompt("你是物流客服。只根据给定的物流记录回答，核对订单号，不猜测送达时间。")
                .build();
             ReActAgent invoiceAgent = ReActAgent.builder()
                     .name("invoice").model(model)
                     .sysPrompt("你是发票客服。只根据给定的开票说明回答，不声称已经开票。")
                     .build()) {

            Route selected = route(question);
            log.info("用户问题：{}；选择的路由：{}", question, selected);

            String answer = switch (selected) {
                case LOGISTICS -> ask(logisticsAgent,
                        "教学物流记录：A001 已到杭州转运中心，暂无预计送达时间。\n用户问题：" + question);
                case INVOICE -> ask(invoiceAgent,
                        "教学开票说明：在订单详情页选择“申请发票”，填写抬头并提交。\n用户问题：" + question);
                case CLARIFY -> "请问您想先查询物流，还是咨询开票？";
            };
            log.info("最终答复：{}", answer);
        }
    }

    private static Route route(String question) {
        boolean logistics = question.contains("包裹") || question.contains("物流") || question.contains("快递");
        boolean invoice = question.contains("发票") || question.contains("开票");

        if (logistics && !invoice) return Route.LOGISTICS;
        if (invoice && !logistics) return Route.INVOICE;
        return Route.CLARIFY; // 两类都命中，或都没命中
    }

    private static String ask(ReActAgent agent, String input) {
        log.info("实际调用 Agent：{}", agent.getName());
        Msg reply = agent.call(input).block(Duration.ofSeconds(120));
        if (reply == null || reply.getTextContent() == null || reply.getTextContent().isBlank()) {
            throw new IllegalStateException(agent.getName() + " 没有返回有效文本");
        }
        return reply.getTextContent();
    }
}