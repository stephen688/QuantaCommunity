package com.quanta.demo0.comment.service;

import com.quanta.demo0.comment.vo.CommentSnapshotVO;

/** 评论计数服务：集中维护内容、回答和评论点赞的同步计数更新。 */
public interface CommentCounterService {

    /** 查询当前评论事实，供其他域做存在性与权限校验。 */
    CommentSnapshotVO getCommentSnapshot(Long commentId);

    /** 调整内容的可见评论数。 */
    int changeCommentCount(Long contentId, int delta);

    /** 调整回答的可见评论数。 */
    int changeAnswerCommentCount(Long answerId, int delta);

    /** 调整评论点赞数。 */
    int changeCommentLikeCount(Long commentId, int delta);
}
