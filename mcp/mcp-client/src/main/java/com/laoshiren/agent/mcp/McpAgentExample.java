package com.laoshiren.agent.mcp;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import lombok.extern.slf4j.Slf4j;

import java.util.Optional;

@Slf4j
public class McpAgentExample {

    public static void main(String[] args) {

        McpClientWrapper client = McpClientBuilder.create("order-client")
                .streamableHttpTransport("http://127.0.0.1:9080/mcp")
                .buildSync();

        Toolkit toolkit = new Toolkit();
        toolkit.registerMcpClient(client).block();

        OpenAIChatModel model = OpenAIChatModel.builder()
                .baseUrl("http://192.168.31.6:32354/v1")
                .modelName("qwen3.8-27b")
                .apiKey("")
                .stream(true)
                .build();

        ReActAgent agent = ReActAgent.builder()
                .name("order-assistant")
                .sysPrompt("""
                        你是一个订单查询助手，请使用中文回答。
                        查询订单状态时，必须调用订单查询工具。
                        用户没有提供订单号时，先询问订单号。
                        根据工具返回的真实结果回答，不要编造订单信息。
                        """)
                .model(model)
                .toolkit(toolkit)
                .build();

        try (agent; client) {
            Msg reply = agent.call(new UserMessage("订单 A001 和 A002，哪个还没有发货？")).block();
            log.info("助手回答：{}", Optional.ofNullable(reply).map(Msg::getTextContent).orElse(""));
        }

    }

}
