package com.quanta.demo0.comment.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 评论域向其他业务域暴露的最小评论事实快照。
 *
 * <p>跨域调用只依赖该 VO，不直接引用评论实体或 CommentMapper。</p>
 *
 * ============================================================
 * 【为什么其他域不直接用 ContentComment 实体？】
 * ============================================================
 * 实体是数据库表的镜像，字段与表结构锁死——跨域直接引用实体，
 * 评论表加一列、改一列都会波及所有调用方。**快照是评论域主动承诺的
 * 对外契约**：interaction 域的点赞 / 举报（CommentInteractionServiceImpl
 * 通过 CommentCounterService.getCommentSnapshot 读取）只看这里声明的字段，
 * 内部表结构怎么演进都被这层隔离。
 *
 * 【快照里为什么连 auditStatus / isDeleted 都给】因为"可不可见"的判断权
 * 留给调用方：interaction 在点赞 / 举报入口可按这两个字段自行决定放行或拒绝，
 * 评论域不用为每个调用方定制"可见性口径"。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CommentSnapshotVO {

    // 以下均为只读事实字段：由 CommentCounterService 从最新库表数据装配，
    // 调用方只读不写——给 VO 传值没有意义，也没有任何接口接受它作为入参。
    private Long commentId;
    private Long contentId;
    private Long answerId;
    private Long parentId;
    private Long replyCommentId;
    private Long replyUserId;
    private Long userId;
    private String content;
    private Integer likeCount;
    private Integer auditStatus;
    private Integer isDeleted;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
