package com.quanta.demo0.rag.model;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

//import j.validation.constraints.NotBlank;
import java.io.Serializable;

/**
 * RAG 搜索请求 DTO
 * 作用：统一前端搜索入参，包含搜索关键词、内容类型过滤、分页参数、AI 开关
 *
 * 使用场景：
 *   前端调用 POST /rag/search 时传入此对象，Controller 层接收后交给 Service 处理
 *
 * 字段说明：
 *   query       - 搜索关键词，必填，不能为空字符串
 *   contentType - 内容类型过滤：1-生活求助 2-专业问答 null-全部
 *   current     - 当前页码，默认 1
 *   pageSize    - 每页条数，默认 10
 *   enableAi    - 是否启用 AI 总结，默认 true
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class RagSearchRequest implements Serializable {

    /**
     * 搜索关键词（必填）
     * 用户输入的原始查询文本，会同时用于 ES 关键词检索和向量语义检索
     */
    @NotBlank(message = "搜索关键词不能为空")
    private String query;

    /**
     * 内容类型过滤（可选）
     * 1-生活求助（小红书模式）
     * 2-专业问答（知乎模式）
     * null-不限制，检索全部内容
     */
    private Integer contentType;


    /**
     * 是否启用 AI 总结（默认 true）
     * true  - 检索后调用 DeepSeek 生成总结
     * false - 仅返回原始帖子列表，不调用 AI，节省 token
     */
    @Builder.Default
    private Boolean enableAi = true;
}