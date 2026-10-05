package com.quanta.demo0.search.es.document;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * ES 内容文档实体类
 * 对应 MySQL 的 tb_content 表
 *
 * ============================================================
 * 【MySQL 与 ES 的字段分工：进 ES 的只有"检索要用"的那部分】
 * ============================================================
 * 本类是 ContentSnapshotVO（content 域跨包快照）的检索子集，由
 * ContentIndexServiceImpl.toDocument 一一映射而来：分词字段 title/content、
 * 过滤字段 auditStatus/isDeleted、计数与时间用于结果展示。**作者昵称头像、
 * tags、updateTime 不进 ES** —— 搜索结果页按 publishUserId 回查作者
 * （ContentSearchServiceImpl 走 AuthorProfileCache），改动这些字段无需重建索引。
 *
 * ============================================================
 * 【esSearchScore 为什么在 mapping 里找不到？】
 * ============================================================
 * 它是"读时计算"的临时字段：只在检索时由 hit.score() 回填（见
 * ContentIndexServiceImpl.searchContent），toSource 不写它，ES 里不落盘；
 * RAG 检索用它与向量召回得分做融合排序（rag.es-weight）。
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class ContentDocument implements Serializable {

    /**
     * 内容唯一 ID（主键）
     *
     * 【重要】映射 tb_content.content_id，同时是 ES 文档 _id —— 同 id 覆盖写
     * 是 upsert 幂等与全量重建可重跑的根基。
     */
    private Long contentId;

    /**
     * 内容类型：1-生活求助 2-专业问答
     *
     * 精确值字段，查询侧可选 filter（ElasticsearchQueryFactory.contentSearch）。
     */
    private Integer contentType;

    /**
     * 问题标题
     *
     * 【分词】索引 ik_max_word / 搜索 ik_smart，并参与高亮（mapping 见
     * ElasticsearchIndexInitializer）。
     */
    private String title;

    /**
     * 问题描述
     *
     * 同 title 的双分词器配置；搜索命中时会被高亮片段替换返回。
     */
    private String content;

    /**
     * 发布用户 ID
     *
     * ES 只存 id；作者昵称头像等资料留在 MySQL，由展示层按 id 回查。
     */
    private Long publishUserId;

    /**
     * 审核状态：0-待审核 1-已通过 2-已驳回
     *
     * 写入侧 isVisible 只放行 1，查询侧再 filter auditStatus=1 双保险；
     * 之所以存进 ES，是为了查询能直接 filter 而不用回 MySQL 验状态。
     */
    private Integer auditStatus;

    /**
     * 点赞数
     *
     * 注意命名错位：tb_content.liked → ContentSnapshotVO.likedCount →
     * 本字段 liked（toDocument 里做的改名），全文检索场景只作展示。
     */
    private Integer liked;

    /**
     * 收藏数
     */
    private Integer collectCount;

    /**
     * 评论数
     */
    private Integer commentCount;

    /**
     * 发布时间
     *
     * mapping 里声明多格式 date；ES 内部存 epoch 毫秒，读回可能是数字，
     * 解析兜底见 ContentIndexServiceImpl.parseCreateTime。
     */
    private LocalDateTime createTime;

    /**
     * 软删除标识：0-未删除 1-已删除
     *
     * 与 auditStatus 同理：写入侧不可见即删文档，查询侧仍 filter isDeleted=0
     * 兜住对账间隙的脏数据。
     */
    private Integer isDeleted;

    /**
     * ES 相关性得分；仅在检索结果中回填，索引 source 不持久化该字段。
     */
    private Double esSearchScore;
}
