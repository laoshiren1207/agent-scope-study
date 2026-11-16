package com.laoshiren.agent.quickstart;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.*;

public class StreamingChatExample extends BaseExample {


    public static void main(String[] args) {
        RuntimeContext context = RuntimeContext.builder()
                .userId("alice")
                .sessionId("streaming-demo")
                .build();

        try (ReActAgent agent = reActAgent(
                openAIChatModel(), toolkit(), agentStateStore())) {

            agent.streamEvents("用 200 字介绍一下机械键盘", context)
                    .doOnNext(StreamingChatExample::handleEvent)
                    .blockLast();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void handleEvent(AgentEvent event) {
        if (event instanceof AgentStartEvent) {
            System.out.println("\t[Agent 开始]");
        } else if (event instanceof ModelCallStartEvent) {
            System.out.println("\t[模型调用开始]");
        } else if (event instanceof TextBlockDeltaEvent text) {
            // 不换行，将收到的文本片段连续显示
            System.out.print(text.getDelta());
            System.out.flush();

        } else if (event instanceof ToolCallStartEvent tool) {
            System.out.println("\t[模型请求工具] " + tool.getToolCallName());

        } else if (event instanceof ToolResultEndEvent result) {
            System.out.printf("\t[工具结果] %s，状态：%s%n", result.getToolCallName(), result.getState());

        } else if (event instanceof AgentEndEvent) {
            System.out.println("\t[Agent 结束]");
        }
    }
}
