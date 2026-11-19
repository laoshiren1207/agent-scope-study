package com.laoshiren.agent.skills.agent;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** 模型配置仅由服务端提供；不生成包含 API Key 的 toString。 */
@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "skills.agent")
public class SkillAgentProperties {
    private String baseUrl = "";
    private String modelName = "";
    private String apiKey = "";
    @Min(1) @Max(10)
    private int maxIters = 4;
    @Min(1) @Max(300)
    private int timeoutSeconds = 60;
}
