package com.laoshiren.agent.quickstart;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.extensions.model.openai.OpenAIChatModel;

public class BasicChatExample extends BaseExample {

    public static void main(String[] args) {
        // AI 模型
        OpenAIChatModel openAIChatModel = openAIChatModel();
        // AI Agent
        ReActAgent reActAgent = reActAgent(openAIChatModel, null, null);
        Msg reply = reActAgent.call(new UserMessage("你好，请介绍一下自己")).block();
        System.out.println(reply);
        System.out.println(reply.getTextContent());
    }


}
