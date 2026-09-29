package com.quanta.demo0.interaction.service;

import com.quanta.demo0.interaction.dto.CommentReportDTO;
import com.quanta.demo0.interaction.vo.LikeResultVO;

import java.util.List;
import java.util.Set;

/** 评论互动服务：处理点赞和举报关系，不承担评论发布、查询或删除。 */
public interface CommentInteractionService {

    /** 设置评论点赞目标状态并返回最新点赞数。 */
    LikeResultVO likeComment(Long commentId, boolean liked);

    /** 查询当前用户在指定评论集合中的点赞关系。 */
    Set<Long> getLikedCommentIds(Long userId, List<Long> commentIds);

    /** 删除指定内容下全部评论的点赞关系。 */
    void deleteByContentId(Long contentId);

    /** 删除单条评论的全部点赞关系。 */
    void deleteByCommentId(Long commentId);

    /** 删除指定回复集合的全部点赞关系。 */
    void deleteByCommentIds(List<Long> commentIds);

    /** 提交评论举报，重复有效举报会被拒绝。 */
    void reportComment(CommentReportDTO commentReportDTO);
}
