package com.quanta.demo0.config.rag;

import com.quanta.demo0.properties.RagProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * Qwen Embedding 模型配置类
 * 使用 OpenAI 兼容模式接入 DashScope
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "rag", name = "enabled", havingValue = "true", matchIfMissing = true)
public class QwenEmbeddingConfig {

    private final RagProperties ragProperties;

    @Value("${spring.ai.qwen-openai.api-key}")
    private String apiKey;

    @Value("${spring.ai.qwen-openai.base-url}")
    private String baseUrl;

    @Value("${spring.ai.qwen-openai.embedding.options.model:text-embedding-v4}")
    private String model;

    @Bean
    public EmbeddingModel qwenEmbeddingModel() {
        if (!StringUtils.hasText(apiKey)) {
            throw new IllegalStateException(
                    "spring.ai.qwen-openai.api-key 未配置：请设置环境变量 QWEN_API_KEY，或在 application-local-secret.yml 中配置 spring.ai.qwen-openai.api-key");
        }
        OpenAiApi openAiApi = new OpenAiApi(baseUrl, apiKey);

        OpenAiEmbeddingOptions options = OpenAiEmbeddingOptions.builder()
                .model(model)
                .build();

        EmbeddingModel embeddingModel = new OpenAiEmbeddingModel(
                openAiApi,
                MetadataMode.EMBED,
                options
        );
        // 首次 embedding 后校验维度
        validateDimension(embeddingModel);

        return embeddingModel;
    }

    /**
     * 校验 Embedding 维度是否与配置一致
     */
    public void validateDimension(EmbeddingModel embeddingModel) {
        int expectedDim = ragProperties.getDimension();
        float[] testEmbedding = embeddingModel.embed("test");
        int actualDim = testEmbedding.length;

        if (actualDim != expectedDim) {
            throw new IllegalStateException(
                    String.format("[RAG] Embedding 维度不匹配: 期望=%d, 实际=%d, 请检查模型配置", expectedDim, actualDim));
        }

        log.info("[RAG] Embedding 维度校验通过: dimension={}", actualDim);
    }
}