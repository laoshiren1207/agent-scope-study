package com.laoshiren.agent.rag;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "rag.example=none")
class RagApplicationTests {

    @Autowired
    private Environment environment;

    @Test
    void applicationLoadsYamlAndDisablesDebugLogs() {
        assertThat(environment.getProperty("spring.application.name")).isEqualTo("agent-scope-study-rag");
        assertThat(environment.getProperty("logging.level.root")).isEqualTo("INFO");

        var logger = LoggerFactory.getLogger("io.agentscope.extensions.model.openai.OpenAIClient");
        assertThat(logger.isDebugEnabled()).isFalse();
        assertThat(logger.isInfoEnabled()).isTrue();
    }
}
