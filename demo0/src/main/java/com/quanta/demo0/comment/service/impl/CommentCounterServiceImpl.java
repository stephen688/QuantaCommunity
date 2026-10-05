package com.quanta.demo0.comment.service.impl;

import com.quanta.demo0.comment.mapper.CommentMapper;
import com.quanta.demo0.comment.entity.ContentComment;
import com.quanta.demo0.comment.service.CommentCounterService;
import com.quanta.demo0.comment.vo.CommentSnapshotVO;
import com.quanta.demo0.answer.service.AnswerCounterService;
import com.quanta.demo0.content.service.ContentCounterService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 评论计数服务实现，统一通过各业务域计数端口执行同步数据库更新。 */
/*
 * ============================================================
 * 【计数列归各自域所有，本类只做端口转发】
 * ============================================================
 * 内容的评论数列在 content 域、回答的评论数列在 answer 域、点赞数列在评论域自己的表上。
 * 评论域要改前两个计数时，委托 ContentCounterService / AnswerCounterService 端口完成，
 * **不直接 UPDATE 别的域的表**——跨域写一律走接口，各域的表结构才敢独立演进。
 *
 * ============================================================
 * 【为什么计数是"同步更新"而不是事后对账？】
 * ============================================================
 * 每个 change* 方法返回受影响行数，调用方（审核通过与驳回、评论删除）在同一个事务里
 * 检查 rows != 1 就抛异常回滚，**计数与评论状态要么同进、要么同退**。
 * 联动链路：发评论入库时为待审、不动计数；审核通过 +1；驳回/撤销通过 -1；
 * 删除一级评论 -(1 + 回复数)。见 CommentAuditServiceImpl / CommentCommandServiceImpl。
 */
@Service
@RequiredArgsConstructor
public class CommentCounterServiceImpl implements CommentCounterService {

    private final CommentMapper commentMapper;
    private final ContentCounterService contentCounterService;
    private final AnswerCounterService answerCounterService;

    /** 查询评论事实快照，不把评论实体泄漏到评论域外。 */
    // 【口径】走 selectById（只滤 is_deleted），待审/驳回的评论也能拿到快照，
    // 是否可见由调用方结合快照里的 auditStatus / isDeleted 自行裁决；
    // 只要纯可见口径时用 selectVisibleById（见 BotCommentServiceImpl）。
    @Override
    public CommentSnapshotVO getCommentSnapshot(Long commentId) {
        return toSnapshot(commentMapper.selectById(commentId));
    }

    /** 调整内容评论总数并返回受影响行数。 */
    // 委托 content 域；SQL 为 set 计数列 = GREATEST(0, 计数列 + #{i})，
    // 行内原子增减且计数不为负（见 ContentMapper.updateCommentCount）
    @Override
    public int changeCommentCount(Long contentId, int delta) {
        return contentCounterService.changeCommentCount(contentId, delta);
    }

    /** 调整回答评论总数并返回受影响行数。 */
    // 委托 answer 域，语义同 changeCommentCount
    @Override
    public int changeAnswerCommentCount(Long answerId, int delta) {
        return answerCounterService.updateCommentCount(answerId, delta);
    }

    /** 调整评论点赞总数并返回受影响行数。 */
    // updateLikeCount：GREATEST(0, like_count + #{i}) 在 MySQL 行内原子完成，
    // 并发点赞/取消互不覆盖，异常输入被钳在 0，点赞数不为负
    @Override
    public int changeCommentLikeCount(Long commentId, int delta) {
        return commentMapper.updateLikeCount(commentId, delta);
    }

    // 只搬运字段构建快照 VO：评论实体不出域，外部域拿到的都是显式声明的契约字段
    private CommentSnapshotVO toSnapshot(ContentComment comment) {
        if (comment == null) {
            return null;
        }
        return CommentSnapshotVO.builder()
                .commentId(comment.getCommentId())
                .contentId(comment.getContentId())
                .answerId(comment.getAnswerId())
                .parentId(comment.getParentId())
                .replyCommentId(comment.getReplyCommentId())
                .replyUserId(comment.getReplyUserId())
                .userId(comment.getUserId())
                .content(comment.getContent())
                .likeCount(comment.getLikeCount())
                .auditStatus(comment.getAuditStatus())
                .isDeleted(comment.getIsDeleted())
                .createTime(comment.getCreateTime())
                .updateTime(comment.getUpdateTime())
                .build();
    }
}
