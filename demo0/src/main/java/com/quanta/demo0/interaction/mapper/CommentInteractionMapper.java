package com.quanta.demo0.interaction.mapper;

import com.quanta.demo0.comment.entity.ContentComment;
import com.quanta.demo0.interaction.entity.CommentReport;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Set;

/** 评论点赞与举报关系表的持久化端口。 */
@Mapper
public interface CommentInteractionMapper {

    /** 查询当前用户对指定评论集合的点赞关系。 */
    Set<Long> selectCommentLikeIds(@Param("userId") Long userId,
                                    @Param("allCommentIds") List<Long> commentIds);

    /** 幂等插入评论点赞关系。 */
    int insertCommentLikes(@Param("commentId") Long commentId, @Param("userId") Long userId);

    /** 删除当前用户的评论点赞关系。 */
    int deleteCommentLikeByUser(@Param("commentId") Long commentId, @Param("userId") Long userId);

    /** 查询当前用户对评论的有效举报。 */
    CommentReport selectValidReportByCommentAndUser(@Param("commentId") Long commentId,
                                                     @Param("reporterId") Long reporterId);

    /** 写入评论举报关系。 */
    void insertCommentReport(CommentReport report);
}
