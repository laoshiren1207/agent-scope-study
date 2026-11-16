package com.laoshiren.agent.quickstart;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.model.openai.OpenAIChatModel;

public class MultiTurnChatExample extends BaseExample {

    public static void main(String[] args) {
        OpenAIChatModel openAIChatModel = openAIChatModel();

        Toolkit toolkit = toolkit();

        ReActAgent reActAgent = reActAgent(openAIChatModel, toolkit, agentStateStore());

        RuntimeContext sessionA = RuntimeContext.builder()
                .userId("alice")
                .sessionId("session-a")
                .build();

        chat(reActAgent, sessionA, "我的订单号是 A001");
        chat(reActAgent, sessionA, "我的订单号是 A002");
        chat(reActAgent, sessionA, "帮我查询刚才那个订单的状态。");
        chat(reActAgent, sessionA, "已发货的订单号是多少。");


        RuntimeContext sessionB = RuntimeContext.builder()
                .userId("alice")
                .sessionId("session-b")
                .build();

        chat(reActAgent, sessionB, "我的订单号是什么？");
    }


}
