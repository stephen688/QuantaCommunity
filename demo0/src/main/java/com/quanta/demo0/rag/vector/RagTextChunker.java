package com.quanta.demo0.rag.vector;

import com.quanta.demo0.properties.RagProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * RAG 文本切分器
 * ============================
 * 作用说明
 * ============================
 * 将长文本按固定窗口 + overlap 策略切分为多个 chunk
 * 策略：
 *   - 文本长度 < minCharsForChunking → 不切分，返回单 chunk
 *   - 文本长度 >= minCharsForChunking → 按 chunkSize 切分，相邻 chunk 重叠 chunkOverlap 字符
 */
@Component
@Slf4j
public class RagTextChunker {

    /**
     * 切分文本
     * ============================
     * @param text       待切分文本
     * @param properties RAG 配置属性
     * @return chunk 列表，至少包含一个元素
     */
    public List<String> chunk(String text, RagProperties properties) {
        if (text == null || text.isEmpty()) {
            return Collections.singletonList("");
        }

        int minChars = properties.getMinCharsForChunking();
        int chunkSize = properties.getChunkSize();
        int overlap = properties.getChunkOverlap();
        int maxChunks = properties.getMaxChunksPerDoc();

        // 短文本不切分
        if (text.length() < minChars) {
            return Collections.singletonList(text);
        }

        List<String> chunks = new ArrayList<>();
        int start = 0;
        int effectiveChunkSize = chunkSize - overlap; // 实际步长

        while (start < text.length()) {
            int end = Math.min(start + chunkSize, text.length());
            String chunk = text.substring(start, end);
            chunks.add(chunk);

            // 超过最大 chunk 数保护
            if (chunks.size() >= maxChunks) {
                log.warn("[RAG-CHUNKER] 文本过长，已截断至 {} 个 chunk，原文本长度={}", maxChunks, text.length());
                break;
            }

            start += effectiveChunkSize;

            // 最后一个 chunk 已处理完
            if (end >= text.length()) {
                break;
            }
        }

        log.info("[RAG-CHUNKER] 文本切分完成: 原文本长度={}, chunk 数={}, chunkSize={}, overlap={}",
                text.length(), chunks.size(), chunkSize, overlap);

        return chunks;
    }
}