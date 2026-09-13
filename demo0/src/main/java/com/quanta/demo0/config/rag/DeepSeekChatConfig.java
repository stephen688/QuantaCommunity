package com.quanta.demo0.config.rag;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * DeepSeek 对话模型配置类
 * 使用 OpenAI 兼容模式接入 DeepSeek
 */
@Configuration
@ConditionalOnProperty(prefix = "rag", name = "ai-enabled", havingValue = "true", matchIfMissing = true)
public class DeepSeekChatConfig {

    @Value("${spring.ai.openai.api-key}")
    private String apiKey;

    @Value("${spring.ai.openai.base-url}")
    private String baseUrl;

    @Value("${spring.ai.openai.chat.options.model:deepseek-chat}")
    private String model;

    @Value("${spring.ai.openai.chat.options.temperature:0.3}")
    private Double temperature;

    @Value("${spring.ai.openai.chat.options.max-tokens:2000}")
    private Integer maxTokens;

    // 修改后：
    @Bean
    public ChatModel deepSeekChatModel() {
        if (!StringUtils.hasText(apiKey)) {
            throw new IllegalStateException(
                    "spring.ai.openai.api-key 未配置：请设置环境变量 OPENAI_API_KEY，或在 application-local-secret.yml 中配置 spring.ai.openai.api-key");
        }
        OpenAiApi openAiApi = new OpenAiApi(baseUrl, apiKey);

        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(model)
                .temperature(temperature)
                .maxTokens(maxTokens)
                .build();

        return OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(options)
                .build();
    }
}