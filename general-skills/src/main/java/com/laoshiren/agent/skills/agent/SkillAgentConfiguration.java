package com.laoshiren.agent.skills.agent;

import io.agentscope.core.model.Model;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.Assert;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SkillAgentProperties.class)
public class SkillAgentConfiguration {
    /** 首次调用时初始化，共享模型客户端；未配置模型不影响技能管理接口启动。 */
    @Bean
    public SkillAgentModelProvider skillAgentModelProvider(SkillAgentProperties properties) {
        return new SkillAgentModelProvider() {
            private Model model;

            @Override
            public synchronized Model getModel() {
                if (model == null) {
                    Assert.hasText(properties.getBaseUrl(), "缺少模型地址");
                    Assert.hasText(properties.getModelName(), "缺少模型名称");
                    Assert.hasText(properties.getApiKey(), "缺少模型 API Key");
                    model = OpenAIChatModel.builder()
                            .baseUrl(properties.getBaseUrl())
                            .modelName(properties.getModelName())
                            .apiKey(properties.getApiKey())
                            .stream(true)
                            .build();
                }
                return model;
            }
        };
    }
}
