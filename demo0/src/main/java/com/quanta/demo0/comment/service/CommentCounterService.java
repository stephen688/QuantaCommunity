package com.quanta.demo0.comment.service;

/** 评论计数服务：集中维护内容、回答和评论点赞的同步计数更新。 */
public interface CommentCounterService {

    /** 调整内容的可见评论数。 */
    int changeCommentCount(Long contentId, int delta);

    /** 调整回答的可见评论数。 */
    int changeAnswerCommentCount(Long answerId, int delta);

    /** 调整评论点赞数。 */
    int changeCommentLikeCount(Long commentId, int delta);
}
