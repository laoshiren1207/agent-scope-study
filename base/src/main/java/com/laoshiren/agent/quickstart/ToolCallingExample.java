package com.laoshiren.agent.quickstart;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.model.openai.OpenAIChatModel;

/**
 *
 */
public class ToolCallingExample extends BaseExample {



    public static void main(String[] args) {


        OpenAIChatModel openAIChatModel = openAIChatModel();

        Toolkit toolkit = toolkit();

        ReActAgent reActAgent = reActAgent(openAIChatModel, toolkit, null);

        Msg reply = reActAgent.call(new UserMessage("帮我查询订单 A002和A001 的状态，发货了没有")).block();

        System.out.println(reply.getTextContent());

    }


}
