package com.laoshiren.agent.skills;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.skill.repository.ClasspathSkillRepository;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class SkillAgentExample {

    public static void main(String[] args) {
        OpenAIChatModel model = new OpenAIChatModel.Builder()
                .baseUrl("http://192.168.31.6:32354/v1")
                .modelName("qwen3.8-27b")
                .apiKey("")
                .stream(true)
                .build();
        try (ClasspathSkillRepository repository = new ClasspathSkillRepository("skills");
             ReActAgent agent = ReActAgent.builder()
                     .name("after-sales-assistant")
                     .sysPrompt("你是售后客服")
                     .model(model)
                     .skillRepository(repository)
                     .build()
        ){

            agent.streamEvents(new UserMessage("我想退款，该怎么办？我的订单是A001"))
                    .doOnNext(event -> {
                        if (event instanceof ToolCallStartEvent call) {
                            log.info("模型请求工具：{}" , call.getToolCallName());
                        } else if (event instanceof ToolResultEndEvent result) {
                            log.info("工具执行状态：{}" , result.getState());
                        } else if (event instanceof AgentResultEvent result) {
                            log.info("回复：{}" , result.getResult().getTextContent());
                        }
                    })
                    .blockLast();


        } catch (Exception ignore){}

    }
}
