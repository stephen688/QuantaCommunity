package com.quanta.demo0.mapper;

import com.github.pagehelper.Page;
import com.quanta.demo0.dto.ContentAdminQueryDTO;
import com.quanta.demo0.dto.ContentReportQueryDTO;
import com.quanta.demo0.entity.*;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface ContentMapper {


    /**
     * 插入内容
     * @param content
     */
    void insert(Content content);

   /**
    * 批量插入内容图片
    * @param images
    */
    void batchInsertImages(@Param("images") List<ContentImage> images);

    /**
     * 根据内容 ID 列表查询对应的图片列表
     * @param contentId 内容 ID 列表
     * @return 图片列表
     */
    @Select("select * from tb_content_image where content_id = #{contentId}")
    List<ContentImage> selectImagesByContentIds(Long contentId);

    /**
     * 根据内容 IDs 列表查询对应的内容列表
     * @param ids 内容 ID 列表
     * @return 内容列表
     */
    List<Content> selectBatchIds( List<Long> ids);

    /**
     * 根据内容 ID 查询内容详情
     * @param contentId 内容 ID
     * @return 内容详情
     */
    @Select("select * from tb_content where content_id = #{contentId} and is_deleted = 0")
    Content selectById(Long contentId);

    /**
     * 根据内容 ID 查询内容详情（加锁）
     * @param contentId 内容 ID
     * @return 内容详情
     */
    @Select("select * from tb_content where content_id = #{contentId} and is_deleted = 0 for update")
    Content selectByIdForUpdate(Long contentId);

    boolean updateLiked(@Param("contentId") Long contentId, @Param("i") int i);

    /**
     * 插入点赞记录
     *
     * @param contentLiked 点赞记录对象
     * @return
     */
    int insertContentLiked(ContentLiked contentLiked);

    /**
     * 根据内容 ID 和用户 ID 删除点赞记录
     * @param contentId 内容 ID
     * @param userId 用户 ID
     */
    @Select("select count(1) from tb_content_like where content_id = #{contentId} and user_id = #{userId}")
    int countContentLiked(@Param("contentId") Long contentId, @Param("userId") Long userId);

    @Delete("delete from tb_content_like where content_id = #{contentId} and user_id = #{userId}")
    int deleteContentLikedByUser(@Param("contentId") Long contentId, @Param("userId") Long userId);

    @Delete("delete from tb_content_like where content_id = #{contentId}")
    void deleteContentLikedByContentId(Long contentId);



    Page<Content> getMyContentsList(@Param("userId") Long userId, @Param("auditStatus") Integer auditStatus);

    void softDeleteContent(Long contentId);

    @Delete("delete from tb_content_image where content_id = #{contentId}")
    void deleteContentImages(Long contentId);

    void deleteContentCommentImages(Long contentId);

    void deleteContentCommentLiked(Long contentId);

    void softDeleteContentComment(Long contentId);

    int insertCollect(ContentCollect contentCollect);

    @Select("select count(1) from tb_content_collect where content_id = #{contentId} and user_id = #{userId}")
    int countContentCollect(@Param("contentId") Long contentId, @Param("userId") Long userId);

    @Delete("delete from tb_content_collect where content_id = #{contentId} and user_id = #{userId}")
    int deleteCollect(@Param("contentId") Long contentId, @Param("userId") Long userId);

    @Select("select * from tb_content where content_id in (select content_id from tb_content_like where user_id = #{userId}) and is_deleted = 0 order by create_time desc")
    Page<Content> getMyLikedContentList(Long userId);

    @Select("select * from tb_content where content_id in (select content_id from tb_content_collect where user_id = #{userId}) and is_deleted = 0 order by create_time desc")
    Page<Content> getMyCollectContentList(Long userId);

    Page<Content> searchContent(@Param("keyword") String keyword,@Param("contentType") Integer contentType);

    @Delete("delete from tb_content_collect where content_id = #{contentId}")
    void deleteContentCollectByContentId(Long contentId);


   int updateCollectCount(@Param("contentId") Long contentId, @Param("i") int i);

    @Select("select * from tb_content   order by create_time desc limit #{offset}, #{batchSize}")
    List<Content> selectAllForReindex(int offset, int batchSize);

    /**
     * 分页查询已通过且未删除的内容，用于推荐流 Redis 冷启动预热。
     */
    @Select("SELECT * FROM tb_content WHERE is_deleted = 0 AND audit_status = 1 ORDER BY create_time DESC LIMIT #{offset}, #{batchSize}")
    List<Content> selectApprovedForRecommendWarmup(@Param("offset") int offset, @Param("batchSize") int batchSize);

    //void insertAnswer(QuestionAnswer answer);

   
    @Select("SELECT * FROM tb_content_report WHERE content_id = #{contentId} AND reporter_id = #{reporterId} AND is_deleted = 0")
    ContentReport selectValidReportByContentAndUser(@Param("contentId") Long contentId, @Param("reporterId") Long reporterId);


    void insertContentReport(ContentReport report);


    Page<Content> pageAdmin(@Param("query") ContentAdminQueryDTO query);

    void update(Content updateContent);

    Page<ContentReport> pageReport(@Param("query") ContentReportQueryDTO query);

    @Select("select * from tb_content_report where id=#{id} and is_deleted = 0")
    ContentReport getReportById(Long reportId);

    void updateReport(ContentReport updateReport);


    /**
     * 分页查询用户已审核通过且未删除的帖子
     * @param userId 用户 ID
     * @return 帖子列表（分页）
     */
    Page<Content> pageUserPublicContents(@Param("userId") Long userId);

    /**
     * 统计用户已审核通过且未删除的帖子数量
     * @param userId 用户 ID
     * @return 帖子数量
     */
    @Select("SELECT COUNT(*) FROM tb_content WHERE publish_user_id = #{userId} AND is_deleted = 0 AND audit_status = 1")
    Integer countUserPublicContents(@Param("userId") Long userId);

    /**
     * 查询点赞数最高的内容（热门问题兜底）
     *
     * @param limit 限制数量
     * @return 内容列表
     */
    @Select("SELECT * FROM tb_content WHERE is_deleted = 0 AND audit_status = 1 ORDER BY liked DESC, create_time DESC LIMIT #{limit}")
    List<Content> selectTopLikedContents(@Param("limit") int limit);

    /**
     * 查询某用户已审核通过的公开帖子（用于关注流回填）
     */
    @Select("SELECT * FROM tb_content WHERE publish_user_id = #{publishUserId} AND is_deleted = 0 AND audit_status = 1 ORDER BY create_time DESC LIMIT #{limit}")
    List<Content> selectApprovedByPublishUserId(@Param("publishUserId") Long publishUserId, @Param("limit") int limit);


    /**
     * AI 审核只允许把待审核状态修改为最终状态。
     *
     * 返回 1：当前线程修改成功。
     * 返回 0：帖子不存在、已删除或者已经被别人审核。
     */
    int updateAuditStatusIfPending(
            @Param("contentId") Long contentId,
            @Param("auditStatus") Integer auditStatus
    );

}
