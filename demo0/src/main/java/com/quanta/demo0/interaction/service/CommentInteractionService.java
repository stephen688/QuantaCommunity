package com.quanta.demo0.interaction.service;

import com.quanta.demo0.interaction.dto.CommentReportDTO;
import com.quanta.demo0.interaction.vo.LikeResultVO;

/** 评论互动服务：处理点赞和举报关系，不承担评论发布、查询或删除。 */
public interface CommentInteractionService {

    /** 设置评论点赞目标状态并返回最新点赞数。 */
    LikeResultVO likeComment(Long commentId, boolean liked);

    /** 提交评论举报，重复有效举报会被拒绝。 */
    void reportComment(CommentReportDTO commentReportDTO);
}
