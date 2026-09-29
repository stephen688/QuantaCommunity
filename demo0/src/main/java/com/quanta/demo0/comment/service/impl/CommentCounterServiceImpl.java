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
@Service
@RequiredArgsConstructor
public class CommentCounterServiceImpl implements CommentCounterService {

    private final CommentMapper commentMapper;
    private final ContentCounterService contentCounterService;
    private final AnswerCounterService answerCounterService;

    /** 查询评论事实快照，不把评论实体泄漏到评论域外。 */
    @Override
    public CommentSnapshotVO getCommentSnapshot(Long commentId) {
        return toSnapshot(commentMapper.selectById(commentId));
    }

    /** 调整内容评论总数并返回受影响行数。 */
    @Override
    public int changeCommentCount(Long contentId, int delta) {
        return contentCounterService.changeCommentCount(contentId, delta);
    }

    /** 调整回答评论总数并返回受影响行数。 */
    @Override
    public int changeAnswerCommentCount(Long answerId, int delta) {
        return answerCounterService.updateCommentCount(answerId, delta);
    }

    /** 调整评论点赞总数并返回受影响行数。 */
    @Override
    public int changeCommentLikeCount(Long commentId, int delta) {
        return commentMapper.updateLikeCount(commentId, delta);
    }

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
