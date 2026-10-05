package com.quanta.demo0.comment.dto;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 管理端 - 评论分页查询入参
 *
 * 【与 C 端查询的差异】这是管理端 GET /admin/comment/page 的 Query 参数对象，
 * 由 Spring 按 setter 绑定（无 @ModelAttribute 也一样）。它**不过滤审核状态、
 * 不强制内容可见**——mapper 的 pageAdmin 只固定 is_deleted=0，
 * 审核员需要看到待审和已驳回的评论，这正是管理端查询存在的意义。
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class CommentAdminQueryDTO {
    /**
     * 页码，默认 1
     */
    // @Builder.Default：保证通过 Builder 构造时不填页码也有默认值 1。

    @Builder.Default
    private Integer pageNum = 1;

    /**
     * 每页数量，默认 10
     */

    @Builder.Default
    private Integer pageSize = 10;

    /**
     * 帖子 ID 筛选（可选）
     */
    // 传了就按 content_id 精确过滤，用于"查看某帖全部评论"的治理视角。

    private Long contentId;

    /**
     * 审核状态筛选（可选）：0-待审核 1-已通过 2-已驳回
     */
    // 不传 = 全部状态混排（管理端特色）；传 0 即"AI 转人工的待办队列"。

    private Integer auditStatus;
}