package com.quanta.demo0.comment.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 内容评论实体类
 * 对应数据库表：tb_content_comment
 *
 * ============================================================
 * 【为什么一层楼里同时有 parentId 和 replyCommentId 两个"父"字段？】
 * ============================================================
 * 两者分工不同：**parentId 只表达"属于哪个一级楼层"**（顶级评论为 null，
 * 服务端禁止父评论本身是二级评论——sendComment 校验 parentComment.getParentId()
 * 非 null 即拒绝），楼层的成员关系靠它圈定；**replyCommentId 表达"我在回复谁"**，
 * 可以指向楼层里任何一条评论，配合 replyUserId 冗余出"被回复人"，
 * 前端渲染"回复 @某人"不用回表查被回复评论。
 * 实际检索规律可见 mapper：按楼层查回复只用 parent_id（selectByParentId），
 * bot 评论链回溯则先看 replyCommentId、为空再退到 parentId（BotCommentServiceImpl.getChain）。
 *
 * 【谁能写哪些字段】这是安全边界：userId 一律取 token 里的
 * BaseContext.getCurrentId()，auditStatus 入库强制 PENDING、likeCount 强制 0
 * （sendComment 第 5 步的 builder 写死），审核四字段只允许 updateAuditStatusIfCurrent
 * 这条条件 UPDATE 改——客户端传什么都影响不到，它们不是 DTO 的搬运结果。
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class ContentComment implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 评论唯一 ID（主键）
     */
    private Long commentId;

    /**
     * 内容 ID（关联 tb_content.content_id）
     */
    private Long contentId;

    /**
     * 回答 ID（关联 tb_answer.answer_id，可选）
     */
    private Long answerId;

    /**
     * 父评论 ID（顶级评论为 null 或 0）
     */
    // 楼层锚点：查询端一律 parent_id IS NULL 识别一级评论（selectFirstLevelComments），
    // bot 链路把 0 也归一成 null（BotCommentServiceImpl.normalizeParentId）。
    private Long parentId;

    /**
     * 被回复的评论 ID（回复评论时使用）
     */
    // 只记录"回复谁"，不改变楼层归属；bot 触发链优先沿它向上回溯。
    private Long replyCommentId;

    /**
     * 被回复的用户 ID
     */
    // 客户端传入的展示冗余，服务端未回表校验它与被回复评论作者一致
    //（sendComment 第 4 步只校验 replyCommentId）；它同时参与 bot 触发判定
    //（BotMentionDetector 用 replyUserId == botUserId 判定 replied）。
    private Long replyUserId;

    /**
     * 评论用户 ID（关联 tb_user.id）
     */
    // 安全字段：只能来自 token（BaseContext.getCurrentId()），绝不由请求体写入。
    private Long userId;

    /**
     * 评论内容
     */
    private String content;

    /**
     * 点赞数量
     */
    // 聚合冗余：明细在 interaction 域的点赞表，这里只存计数供列表直读；
    // 初始 0，赞/删操作通过 CommentInteractionService 联动增减。
    private Integer likeCount;

    /**
     * 审核状态：0-待审核 1-已通过 2-已驳回
     */
    // 取值即 platform.common.enums.AuditStatus 的 code。所有 C 端 / bot 查询
    // 都带 audit_status=1 过滤；状态迁移必须走条件 UPDATE（旧状态命中才更新），
    // 防 AI 与管理员并发互踩。
    private Integer auditStatus;

    /**
     * 审核驳回原因
     */
    private String rejectReason;

    /**
     * 审核时间
     */
    private LocalDateTime auditTime;

    /**
     * 审核用户 ID
     */
    // AI 自动通过时写 null，人工审核写管理员 ID（BaseContext），用于审计区分。
    private Long auditUserId;

    /**
     * 是否删除：0-未删除 1-已删除
     */
    // 软删标记：删除链路只把这一位置 1，行保留；因此每条查询 SQL 都显式带
    // is_deleted = 0，漏写就会出现"已删评论复活"。
    private Integer isDeleted;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;

}