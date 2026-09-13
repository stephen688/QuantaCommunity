package com.quanta.demo0.mapper;

import com.github.pagehelper.Page;
import com.quanta.demo0.dto.CommentAdminQueryDTO;
import com.quanta.demo0.dto.CommentReportQueryDTO;
import com.quanta.demo0.entity.CommentImage;
import com.quanta.demo0.entity.CommentReport;
import com.quanta.demo0.entity.ContentComment;
import com.quanta.demo0.entity.ReplyCountRow;
import org.apache.ibatis.annotations.*;

import java.util.Collection;
import java.util.List;
import java.util.Set;

@Mapper
public interface CommentMapper {

    /**
     * 根据父评论 ID
     * @param commentId
     * @return 评论对象
     */
    @Select("select * from tb_content_comment where comment_id = #{commentId} and is_deleted = 0")
    ContentComment selectById(Long commentId);

    /**
     * 根据被回复的评论 ID 查询评论
   //  * @param replyCommentId 被回复的评论 ID
     * @return 评论对象
     */
   // @Select("select * from tb_content_comment where reply_comment_id = #{replyCommentId} and is_deleted = 0")
   // ContentComment selectByReplyCommentId(Long replyCommentId);

    /**
     * 插入评论
     * @param contentComment
     */
    void insert(ContentComment contentComment);

    /**
     * 批量插入评论图片
     * @param images
     */
    void insertCommentImagesBatch(List<CommentImage> images);

    //更新内容表评论数
    @Update("update tb_content set comment_count =GREATEST(0, comment_count + #{i}) where content_id = #{contentId} and is_deleted = 0")
    int updateCommentCount(@Param("contentId") Long contentId,@Param("i") int i);

    //更新点赞数
    @Update("update tb_content_comment set like_count = GREATEST(0, like_count + #{i}) where comment_id = #{commentId} and is_deleted = 0")
    int updateLikeCount(@Param("commentId") Long commentId,@Param("i") int i);


    /**
     * 查询一级评论
     * @param contentId
     * @param answerId
     * @param sortType
     * @return
     */
    Page<ContentComment> selectFirstLevelComments(Long contentId, Long answerId, int sortType);

    /**
     * 查询回复评论数量
     * @param parentIds
     * @return
     */
    List<ReplyCountRow> selectReplyCountsByParentIds(List<Long> parentIds);

    /**
     * 查询回复评论（3条）
     * @param parentIds
     * @param i
     * @return
     */
    List<ContentComment> selectTopRepliesByParentIds(List<Long> parentIds, int i);

    /**
     * 查询用户点赞的评论 ID 列表
     * @param userId 用户 ID
     * @param allCommentIds 所有评论 ID 列表
     * @return 点赞的评论 ID 列表
     */
    Set<Long> selectCommentLikeIds(@Param("userId") Long userId, @Param("allCommentIds") List<Long> allCommentIds);

   //软删除评论
    void softDeleteById(Long commentId);

    //删除评论图片
    @Delete("delete from tb_comment_image where comment_id = #{commentId}")
    void deleteCommentImages(Long commentId);

    //删除评论点赞
    @Delete("delete from tb_comment_like where comment_id = #{commentId}")
    void deleteCommentLikes(Long commentId);


    //查询回复评论ID列表
    @Select("select comment_id from tb_content_comment where parent_id = #{commentId} and is_deleted = 0")
    List<Long> selectReplyIdsByParentId(Long commentId);

    //软删除回复评论
    void softDeleteRepliesByCommentIds(List<Long> replyCommentIds);

    //删除回复评论图片
    void deleteCommentImagesByCommentIds(List<Long> replyCommentIds);

    //删除回复评论点赞
    void deleteCommentLikesByCommentIds(List<Long> replyCommentIds);

    //插入评论点赞

    int insertCommentLikes(@Param("commentId") Long commentId, @Param("userId") Long userId);

    //删除评论点赞
    @Delete("delete from tb_comment_like where comment_id = #{commentId} and user_id = #{userId}")
    int deleteCommentLikeByUser(@Param("commentId") Long commentId, @Param("userId") Long userId);


    Page<ContentComment> selectByParentId(Long parentCommentId,int sortType);

    @Select("SELECT * FROM tb_comment_report WHERE comment_id = #{commentId} AND reporter_id = #{reporterId} AND is_deleted = 0")
    CommentReport selectValidReportByCommentAndUser(Long commentId, Long reporterId);


    void insertCommentReport(CommentReport report);

    Page<ContentComment> pageAdmin(@Param("query") CommentAdminQueryDTO query);

    Page<CommentReport> pageReport(@Param("query") CommentReportQueryDTO query);

    @Select("select * from tb_comment_report where id=#{reportId} and is_deleted = 0")
    CommentReport getReportById(@Param("reportId") Long reportId);


    void updateReport(CommentReport updateReport);


    /**
     * 只有审核状态仍等于旧值时才更新，防止 AI 与管理员互相覆盖。
     */
    int updateAuditStatusIfCurrent(@Param("commentId") Long commentId, @Param("oldAuditStatus") Integer oldAuditStatus,
                                   @Param("newAuditStatus") Integer newAuditStatus, @Param("rejectReason") String rejectReason,
                                   @Param("auditUserId") Long auditUserId);

    /**
     * 根据评论 ID 查询图片 URL 列表（图片审核兜底用）
     */
    List<String> selectImagesByCommentId(@Param("commentId") Long commentId);
}
