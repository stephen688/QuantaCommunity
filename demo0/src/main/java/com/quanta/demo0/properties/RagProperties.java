package com.quanta.demo0.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Min;

/**
 * RAG 业务配置属性类
 * 承载 rag.* 配置参数（topK、阈值、开关、权重、文件路径）
 */
@Data
@Component
@ConfigurationProperties(prefix = "rag")
@Validated
public class RagProperties {

    /**
     * RAG 总开关
     */
    private boolean enabled = true;

    /**
     * AI 生成开关
     */
    private boolean aiEnabled = true;

    /**
     * 向量维度（必须与 Embedding 模型输出一致）
     */
    private int dimension = 1024;

    /**
     * 向量文件持久化路径
     */
    private String vectorFilePath = "./data/vector-store/content-vector-store.json";

    /**
     * 启动时是否全量重建向量库
     */
    private boolean bootstrapEnabled = false;

    /**
     * ES 召回数量
     */
    @Min(1)
    private int esTopK = 20;

    /**
     * 向量召回数量
     */
    @Min(1)//min 注解：限制数值必须大于等于 1，确保 topK 的合理性
    private int vectorTopK = 20;

    /**
     * 融合后截断数量
     */
    @Min(1)
    private int finalTopK = 10;

    /**
     * ES 权重
     */
    @Min(0)
    private double esWeight = 0.6;

    /**
     * 向量权重
     */
    @Min(0)
    private double vectorWeight = 0.4;

    /**
     * 两路都命中的来源加分（Plan 要求 0.05）
     */
    @Min(0)
    private double sourceBonus = 0.05;
    /**
     * 总结缓存开关
     */
    private boolean summaryCacheEnabled = true;

    /**
     * 总结缓存 TTL（秒）
     */
    private long summaryCacheTtlSeconds = 1800;

    /**
     * 总结缓存 query 是否转小写
     */
    private boolean summaryCacheNormalizeCase = true;

    /**
     * Chunk 大小（字符数）
     */
    private int chunkSize = 500;

    /**
     * Chunk 重叠字符数
     */
    private int chunkOverlap = 100;

    /**
     * 触发 chunk 的最小字符数（低于此值不切分）
     */
    private int minCharsForChunking = 300;

    /**
     * 单文档最大 chunk 数（兜底保护）
     */
    private int maxChunksPerDoc = 64;
}