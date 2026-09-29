package com.quanta.demo0.comment.mapper;

import com.github.pagehelper.Page;
import com.quanta.demo0.comment.dto.CommentAdminQueryDTO;
import com.quanta.demo0.comment.entity.CommentImage;
import com.quanta.demo0.comment.entity.ContentComment;
import com.quanta.demo0.comment.entity.ReplyCountRow;
import org.apache.ibatis.annotations.*;

import java.util.List;

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

   //软删除评论
    void softDeleteById(Long commentId);

    //删除评论图片
    @Delete("delete from tb_comment_image where comment_id = #{commentId}")
    void deleteCommentImages(Long commentId);

    /** 删除指定内容下评论图片并软删除评论；供内容域通过 CommentCommandService 调用。 */
    void deleteContentCommentImages(@Param("contentId") Long contentId);

    /** 软删除指定内容下全部评论；供内容删除事务调用。 */
    void softDeleteContentComment(@Param("contentId") Long contentId);

    //查询回复评论ID列表
    @Select("select comment_id from tb_content_comment where parent_id = #{commentId} and is_deleted = 0")
    List<Long> selectReplyIdsByParentId(Long commentId);

    //软删除回复评论
    void softDeleteRepliesByCommentIds(List<Long> replyCommentIds);

    //删除回复评论图片
    void deleteCommentImagesByCommentIds(List<Long> replyCommentIds);

    Page<ContentComment> selectByParentId(Long parentCommentId,int sortType);


    Page<ContentComment> pageAdmin(@Param("query") CommentAdminQueryDTO query);



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

    @Select("select * from tb_content_comment where comment_id = #{commentId} and audit_status = 1 and is_deleted = 0")
    ContentComment selectVisibleById(Long commentId);

    @Select("select * from tb_content_comment where user_id = #{userId} and content_id = #{postId} and audit_status = 1 and is_deleted = 0 order by create_time asc, comment_id asc limit #{offset}, #{limit}")
    List<ContentComment> selectBotHistory(@Param("userId") Long userId,
                                          @Param("postId") Long postId,
                                          @Param("offset") int offset,
                                          @Param("limit") int limit);

    @Select("select count(*) from tb_content_comment where user_id = #{userId} and content_id = #{postId} and audit_status = 1 and is_deleted = 0")
    long countBotHistory(@Param("userId") Long userId, @Param("postId") Long postId);

    @Select("select * from tb_content_comment where content_id = #{postId} and audit_status = 1 and is_deleted = 0 order by create_time asc, comment_id asc limit #{offset}, #{limit}")
    List<ContentComment> selectBotFloorsAsc(@Param("postId") Long postId,
                                            @Param("offset") int offset,
                                            @Param("limit") int limit);

    @Select("select * from tb_content_comment where content_id = #{postId} and audit_status = 1 and is_deleted = 0 order by create_time desc, comment_id desc limit #{offset}, #{limit}")
    List<ContentComment> selectBotFloorsDesc(@Param("postId") Long postId,
                                             @Param("offset") int offset,
                                             @Param("limit") int limit);

    @Select("select count(*) from tb_content_comment where content_id = #{postId} and audit_status = 1 and is_deleted = 0")
    long countBotFloors(@Param("postId") Long postId);

    List<CommentImage> selectImagesByCommentIds(@Param("commentIds") List<Long> commentIds);
}
