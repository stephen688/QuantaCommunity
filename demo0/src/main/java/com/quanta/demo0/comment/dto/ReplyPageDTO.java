package com.quanta.demo0.comment.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 二级评论（回复）分页查询入参，服务 GET /comment/replyList。
 *
 * 【校验在服务端，不在字段上】replyPage 第 2 步会逐一核对：
 * parentCommentId 必须真是一级评论（其 parentId 非 null 即拒绝）、
 * contentId 必须与父评论所属内容一致、专业区评论的 answerId 必传且与
 * 父评论一致——查询参数同样不能免检，否则可以跨帖翻别人的楼层。
 */
@Data
public class ReplyPageDTO implements Serializable {

    /**
     * 一级评论 ID（必填）
     */
    private Long parentCommentId;

    /**
     * 内容 ID（必填）
     */
    // 与 parentCommentId 配套的一致性锚点：防止拿 A 帖的 ID 翻 B 帖评论的回复。
    private Long contentId;

    /**
     * 回答 ID（可选）
     */
    // 专业区（contentType=2）必传且必须等于父评论的 answerId，生活区不传。
    private Long answerId;

    /**
     * 当前页码（默认 1）
     */
    private Integer pageNum = 1;

    /**
     * 每页数量（默认 10）
     */
    private Integer pageSize = 10;

    /**
     * 排序方式：1-正序，2-倒序
     */
    // 落到 SQL 是 create_time 的 ASC/DESC（selectByParentId 的 choose 分支），
    // 兜底：非法值一律按 1 正序处理。注意与一级评论列表 CommentPageDTO 的
    // sortType（2=点赞倒序）含义不同。
    private Integer sortType = 1;
}