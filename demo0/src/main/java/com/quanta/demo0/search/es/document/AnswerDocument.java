package com.quanta.demo0.search.es.document;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * ES 回答文档实体类
 * 对应 MySQL 的 tb_question_answer 表
 *
 * ============================================================
 * 【一次写入要同时看回答和父问题两张表的状态】
 * ============================================================
 * 由 AnswerSearchServiceImpl.toDocument(answer, question) 组装：回答快照取自
 * tb_question_answer，questionTitle 冗余取自父问题 tb_content.title。
 * **回答可见、父问题也可见才写入**，任一不可见就删文档 —— 搜出来的回答
 * 点进去必须能看到，否则就是搜索返回死链。
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class AnswerDocument implements Serializable {

    /**
     * 回答唯一 ID（主键）
     *
     * 同时是 ES 文档 _id：重复写入覆盖，对账消息重放幂等。
     */
    private Long answerId;

    /**
     * 所属问题 ID（关联 tb_content.content_id）
     */
    private Long questionId;

    /**
     * 问题标题（冗余字段，便于 BM25 检索）
     *
     * 回答表本身没有标题，这里冗余父问题 tb_content.title，让"搜问题标题
     * 也能召回回答"；父问题改名后要靠对账事件刷新。
     */
    private String questionTitle;

    /**
     * 回答正文
     *
     * 与 questionTitle 同为双 ik 分词字段（写入 ik_max_word / 搜索 ik_smart）。
     */
    private String answerContent;

    /**
     * 回答发布用户 ID
     *
     * ES 只存 id，作者资料由展示层回查 MySQL。
     */
    private Long userId;

    /**
     * 审核状态：0-待审核 1-已通过 2-已驳回
     *
     * 写入侧只放行 1，查询侧 filter auditStatus=1 双保险（同内容侧口径）。
     */
    private Integer auditStatus;

    /**
     * 点赞数
     */
    private Integer likeCount;
    /**
     * ES 检索得分（用于 RAG 融合排序）
     */
    private Double esSearchScore;

    /**
     * 评论数
     */
    private Integer commentCount;

    /**
     * 是否被采纳：0-未采纳 1-已采纳
     */
    private Integer isAccepted;

    /**
     * 发布时间
     */
    private LocalDateTime createTime;

    /**
     * 软删除标识：0-未删除 1-已删除
     *
     * 查询侧仍 filter isDeleted=0，兜住对账间隙的脏数据（同内容侧）。
     */
    private Integer isDeleted;
}
