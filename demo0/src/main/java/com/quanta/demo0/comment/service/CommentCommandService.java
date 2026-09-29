package com.quanta.demo0.comment.service;

import com.quanta.demo0.comment.dto.CommentAddDTO;

/** 评论命令服务：承载评论发布与删除，不负责查询或互动关系。 */
public interface CommentCommandService {

    /** 发布评论并按审核配置写入相应 Outbox。 */
    Long sendComment(CommentAddDTO commentAddDTO);

    /** 删除评论及其回复、图片、点赞明细，并同步派生计数。 */
    void deleteComment(Long commentId);

    /** 删除指定内容下的评论图片、点赞明细并软删除评论。 */
    void deleteByContentId(Long contentId);
}
