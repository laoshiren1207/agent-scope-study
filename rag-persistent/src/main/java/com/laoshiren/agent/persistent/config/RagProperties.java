package com.laoshiren.agent.persistent.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Data
@Validated
@ConfigurationProperties(prefix = "rag")
public class RagProperties {
    @Valid
    private Embedding embedding = new Embedding();
    @Valid
    private Chat chat = new Chat();
    @Valid
    private Rerank rerank = new Rerank();

    /** TEI 重排服务配置；数量和阈值独立于向量召回参数。 */
    @Data
    public static class Rerank {
        /** 是否启用 TEI 重排；关闭时直接使用向量召回结果生成回答。 */
        private boolean enable = true;
        @NotBlank
        private String baseUrl = "http://localhost:8001";
        @Min(1)
        private int timeoutSeconds = 60;
        @Min(1) @Max(100)
        private int topK = 3;
        @DecimalMin("0.0") @DecimalMax("1.0")
        private double minScore = 0.5;
    }

    @Data
    public static class Embedding {
        @NotBlank
        private String baseUrl;

        private String apiKey;
        @NotBlank @Size(max = 128)
        private String model;
        @NotBlank @Size(max = 128)
        private String profile;
        /** 应用校验返回向量的期望维度；不发送给 vLLM，也不会在本地截断向量。 */
        @Min(1) @Max(16000)
        private int dimensions = 4096;
        @Min(1)
        private int timeoutSeconds = 60;
    }

    @Data
    public static class Chat {
        @NotBlank
        private String baseUrl;

        private String apiKey;
        @NotBlank
        private String model;
        @Min(1)
        private int timeoutSeconds = 120;
    }
}
