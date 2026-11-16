package com.laoshiren.agent.springboot.config;

import com.laoshiren.agent.quickstart.BaseExample;
import com.laoshiren.agent.quickstart.MiddlewareExample;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AgentConfig {

    @Bean
    public OpenAIChatModel chatModel() {
        return BaseExample.openAIChatModel();
    }

    @Bean
    public AgentStateStore chatStateStore() {
        return BaseExample.agentStateStore();
    }

    @Bean
    public Toolkit toolkit() {
        return BaseExample.toolkit();
    }

    @Bean(destroyMethod = "close")
    public ReActAgent reActAgent(OpenAIChatModel chatModel,
                                 AgentStateStore chatStateStore,
                                 Toolkit toolkit) {
        return BaseExample.reActAgent(chatModel, toolkit, chatStateStore, new MiddlewareExample.ModelTimingMiddleware());
    }

}
