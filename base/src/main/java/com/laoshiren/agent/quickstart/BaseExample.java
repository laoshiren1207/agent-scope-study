package com.laoshiren.agent.quickstart;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.state.JsonFileAgentStateStore;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import lombok.Data;

import java.nio.file.Path;

public class BaseExample {


    public static OpenAIChatModel openAIChatModel() {
        return OpenAIChatModel.builder()
                .baseUrl("http://192.168.31.6:32354/v1")
                .modelName("qwen3.8-27b")
                .apiKey("")
                .stream(true)
                .build();
    }


    public static ReActAgent reActAgent(OpenAIChatModel aiChatModel) {
        return reActAgent(aiChatModel, null, null, null);
    }

    public static ReActAgent reActAgent(OpenAIChatModel aiChatModel, Toolkit toolkit) {
        return reActAgent(aiChatModel, toolkit, null, null);
    }

    public static ReActAgent reActAgent(OpenAIChatModel aiChatModel, Toolkit toolkit, AgentStateStore agentStateStore) {
        return reActAgent(aiChatModel, toolkit, agentStateStore, null);
    }

    public static ReActAgent reActAgent(OpenAIChatModel aiChatModel, Toolkit toolkit, AgentStateStore agentStateStore, MiddlewareBase middlewareBase) {
        return ReActAgent.builder()
                .name("assistant")
                .sysPrompt("你是一个中文助手，请简洁回答。")
                .model(aiChatModel)
                .toolkit(toolkit == null ? new Toolkit() : toolkit)
                .stateStore(agentStateStore)
                .middleware(middlewareBase)
                .build();
    }


    public static Toolkit toolkit() {

        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new OrderTools());

        return toolkit;
    }

    public static AgentStateStore agentStateStore() {
        return new InMemoryAgentStateStore();
    }

    public static AgentStateStore agentFileStateStore() {
        Path stateDirectory = Path.of(".agentscope", "state")
                .toAbsolutePath();
        return new JsonFileAgentStateStore(stateDirectory);
    }


    public static void chat(ReActAgent agent, RuntimeContext context, String text) {

        System.out.println("用户 [" + context.getSessionId() + "]：" + text);
        Msg reply = agent.call(text, context).block();
        System.out.println("助手：" + reply.getTextContent());
        System.out.println();
    }

    public static class OrderTools {

        @Tool(name = "query_order", description = "根据订单号查询订单状态", readOnly = true)
        public String queryOrder(
                @ToolParam(name = "orderId", description = "订单号，例如 A001") String orderId) {
            System.err.println("\t\t[工具被调用] orderId=" + orderId);
            // 演示数据，后续可以替换成数据库或业务接口
            if ("A001".equals(orderId)) {
                return "订单 A001：已发货，商品为机械键盘。";
            }
            return "未查询到订单：" + orderId;
        }
    }

    @Data
    public static class OrderInfo {

        public String orderId;
        public String productName;
        public Integer quantity;

        @JsonProperty(value = "buyer", required = true)
        public String buyer;
    }
}
