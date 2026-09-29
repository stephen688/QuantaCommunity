package com.quanta.demo0.comment.service;

import com.quanta.demo0.comment.dto.CommentPageDTO;
import com.quanta.demo0.comment.dto.ReplyPageDTO;
import com.quanta.demo0.comment.vo.CommentPageVO;
import com.quanta.demo0.comment.vo.CommentSnapshotVO;

/** 评论查询服务：承载一级评论和回复分页查询。 */
public interface CommentQueryService {

    /** 返回当前评论事实；不存在时返回 null。 */
    CommentSnapshotVO getCommentSnapshot(Long commentId);

    /** 查询内容下的一级评论及其预览回复。 */
    CommentPageVO commentPage(CommentPageDTO commentPageDTO);

    /** 查询指定一级评论下的回复列表。 */
    CommentPageVO replyPage(ReplyPageDTO replyPageDTO);
}
