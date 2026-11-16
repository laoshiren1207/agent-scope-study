package com.laoshiren.agent.quickstart;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.model.openai.OpenAIChatModel;

import java.util.Scanner;

public class PersistentChatExample extends BaseExample {

    public static void main(String[] args) {
        AgentStateStore agentStateStore = agentFileStateStore();


        OpenAIChatModel openAIChatModel = openAIChatModel();

        Toolkit toolkit = toolkit();

        RuntimeContext context = RuntimeContext.builder()
                .userId("alice")
                .sessionId("persistent-demo")
                .build();

        try (ReActAgent agent = reActAgent(openAIChatModel, toolkit, agentStateStore);
             Scanner scanner = new Scanner(System.in)) {

            System.out.println("请输入消息，输入 exit 退出：");

            while (scanner.hasNextLine()) {
                String input = scanner.nextLine().trim();

                if ("exit".equalsIgnoreCase(input)) {
                    break;
                }

                if (!input.isEmpty()) {
                    chat(agent, context, input);
                }
            }
        }
    }

}
