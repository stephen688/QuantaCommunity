package com.quanta.demo0.config.rag;

import com.quanta.demo0.properties.RagProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;

import java.io.File;

/**
 * RAG 基础配置类
 * 启动时检查向量存储目录是否存在，不存在则创建
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class RagConfig {

    private final RagProperties ragProperties;

    @PostConstruct
    public void initVectorStoreDirectory() {
        String filePath = ragProperties.getVectorFilePath();
        File file = new File(filePath);
        File parentDir = file.getParentFile();

        if (parentDir != null && !parentDir.exists()) {
            boolean created = parentDir.mkdirs();
            if (created) {
                log.info("[RAG] 向量存储目录已创建: {}", parentDir.getAbsolutePath());
            } else {
                log.error("[RAG] 向量存储目录创建失败: {}", parentDir.getAbsolutePath());
            }
        } else {
            log.info("[RAG] 向量存储目录已存在: {}", parentDir != null ? parentDir.getAbsolutePath() : "无父目录");
        }
    }
}