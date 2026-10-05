package com.quanta.demo0.comment.service;

import com.quanta.demo0.comment.dto.CommentAddDTO;

/** 评论命令服务：承载评论发布与删除，不负责查询或互动关系。 */
// 【CQS 切分】本接口只收"改状态"的写请求：读评论走 CommentQueryService，
// 点赞/举报等互动走 CommentInteractionService。实现见 CommentCommandServiceImpl；
// 内容域删除帖子时通过 deleteByContentId 把评论清理并入内容删除事务
// （实际调用方：ContentCommandServiceImpl.deleteContent / AdminContentServiceImpl）。
public interface CommentCommandService {

    /** 发布评论并按审核配置写入相应 Outbox。 */
    Long sendComment(CommentAddDTO commentAddDTO);

    /** 删除评论及其回复、图片、点赞明细，并同步派生计数。 */
    // 权限模型（评论本人/题主/答主三级放行）与级联细节见 CommentCommandServiceImpl.deleteComment。
    void deleteComment(Long commentId);

    /** 删除指定内容下的评论图片、点赞明细并软删除评论。 */
    void deleteByContentId(Long contentId);
}
