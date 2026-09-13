package com.quanta.demo0.config.rag;

import com.quanta.demo0.properties.RagProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.File;

/**
 * 向量存储配置类
 * 创建 SimpleVectorStore 并绑定本地 JSON 持久化
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class VectorStoreConfig {

    private final RagProperties ragProperties;

    @Bean
    public SimpleVectorStore simpleVectorStore(EmbeddingModel qwenEmbeddingModel) {
        return SimpleVectorStore.builder(qwenEmbeddingModel).build();
    }
}