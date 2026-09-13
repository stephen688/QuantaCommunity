package com.quanta.demo0.rag.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * RAG 上下文文档对象
 * 作用：用于向量入库和 AI Prompt 引用的标准文档结构
 *
 * 使用场景：
 *   1. 帖子发布/编辑时：从 Content 实体转换为此对象，再转为 Spring AI 的 Document 存入向量库
 *   2. AI 生成阶段：从向量库检索出此对象，提取 content 字段组装到 Prompt 中
 *
 * 与 Content 实体的区别：
 *   - 只保留向量检索和 AI 引用需要的字段，去掉图片、点赞等无关字段
 *   - 文本字段（title + content）会拼接后用于向量化
 *   - 元数据（metadata）中存储 contentId、contentType 等用于过滤
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class RagContextDocument implements Serializable {

    /**
     * 帖子唯一 ID
     * 作为 Document 的 id，用于向量库中的增删改查
     */
    private Long contentId;
    /**
     * 回答 ID（仅回答类文档有值，帖子类为 null）
     * 用于区分文档来源，检索时标记 docKind=ANSWER
     */
    private Long answerId;
    /**
     * 帖子标题
     * 用于向量化和 AI Prompt 引用
     */
    private String title;

    /**
     * 帖子正文
     * 用于向量化和 AI Prompt 引用
     * 注意：过长文本会在向量化前截断
     */
    private String content;

    /**
     * 帖子标签（可选）暂时不用
     * 用于向量化和 AI 理解帖子主题
     * 多个标签用逗号分隔
     */
    //private String tags;

    /**
     * 发布用户 ID
     * 存储在 Document metadata 中，用于后续关联查询用户信息
     */
    private Long publishUserId;

    /**
     * 发布时间
     * 存储在 Document metadata 中，用于融合排序时同分按时间降序
     */
    private LocalDateTime createTime;

    /**
     * 内容类型：1-生活求助 2-专业问答
     * 存储在 Document metadata 中，用于检索时按 contentType 过滤
     */
    private Integer contentType;
}