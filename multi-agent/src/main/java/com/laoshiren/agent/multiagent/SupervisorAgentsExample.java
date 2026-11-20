package com.laoshiren.agent.multiagent;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import java.time.Duration;

public class SupervisorAgentsExample {
    public enum Action { CHECK_POLICY, ASK_USER, FINISH }
    public record Decision(Action action, String message) {}
    private static final Duration TIMEOUT = Duration.ofSeconds(120);

    public static void main(String[] args) {
        var model = OpenAIChatModel.builder()
                .baseUrl("http://192.168.31.6:32354/v1").modelName("qwen3.8-27b")
                .apiKey("").stream(false).build();

        String question = args.length == 0 ? "订单 A001 能退款吗？" : String.join(" ", args);
        String order = "A001 已签收，订单系统不记录商品是否拆封。";
        String policy = null;

        try (var supervisor = ReActAgent.builder().name("supervisor").model(model)
                .sysPrompt("""
                             你是售后主管，每轮只选一个动作。
                             没有政策时选 CHECK_POLICY，message 写要查的政策问题。
                             有政策但缺少影响判断的事实时选 ASK_USER，message 只问缺失的信息。
                             事实和政策足够时选 FINISH，message 直接回答用户。
                             用户已提供的信息不要再问。只能说明能否申请，不能声称退款已办理。
                             """).build();
             var policyAgent = ReActAgent.builder().name("policy").model(model)
                     .sysPrompt("""
                             你只回答政策问题，依据以下教学条款，不补充其他规则：
                             已签收且未拆封的商品可申请退货退款；已拆封的商品需要人工审核。
                             """).build()) {

            for (int round = 1; round <= 3; round++) {
                String state = "用户问题：" + question + "\n订单事实：" + order
                        + "\n已查政策：" + (policy == null ? "尚未查询" : policy);
                Msg msg = supervisor.call(state, Decision.class).block(TIMEOUT);
                if (msg == null || !msg.hasStructuredData()) throw new IllegalStateException("主管未返回决策");
                Decision d = msg.getStructuredData(Decision.class);
                if (d == null || d.action() == null || d.message() == null || d.message().isBlank())
                    throw new IllegalStateException("主管决策缺少动作或消息");

                System.out.printf("第 %d 轮：%s%n", round, d.action());
                switch (d.action()) {
                    case CHECK_POLICY -> {
                        if (policy != null) throw new IllegalStateException("重复查询同一政策");
                        Msg result = policyAgent.call(d.message()).block(TIMEOUT);
                        if (result == null || result.getTextContent() == null || result.getTextContent().isBlank())
                            throw new IllegalStateException("未查到政策");
                        policy = result.getTextContent(); // 下一轮主管会读到它
                    }
                    case ASK_USER, FINISH -> {
                        if (policy == null) throw new IllegalStateException("尚未查询政策");
                        System.out.println(d.message());
                        return;
                    }
                }
            }
            throw new IllegalStateException("主管超过调度轮数");
        }
    }
}