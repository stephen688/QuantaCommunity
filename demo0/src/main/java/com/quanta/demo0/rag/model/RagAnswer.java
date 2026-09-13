package com.quanta.demo0.rag.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * RAG AI 生成结果对象
 * 作用：封装 DeepSeek 生成的总结内容，成功和失败统一输出结构
 *使用场景：
 *   RagAiGenerationService 调用 DeepSeek 后，将结果封装为此对象返回
 *   成功时：enabled=true, content=总结文本, reason=null
 *   失败时：enabled=false, content=null, reason=失败原因
 * 兜底策略：
 *   AI 调用失败不影响主搜索流程，仅 aiAnswer.enabled=false
 *   前端根据 enabled 字段决定是否展示 AI 总结模块
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class RagAnswer implements Serializable {

    /**
     * AI 总结是否成功生成
     * true  - 生成成功，content 字段有值
     * false - 生成失败或未启用，content 为 null
     */
    private Boolean enabled;

    /**
     * AI 生成的总结内容
     * 仅在 enabled=true 时有值
     * 格式：小红书口语化风格，分点总结检索到的帖子信息
     */
    private String content;

    /**
     * 失败原因（可选）
     * 仅在 enabled=false 时有值
     * 用于日志记录和排查问题，不返回给前端
     * 常见原因：API 调用超时、无检索结果、模型返回异常等
     */
    private String reason;
}