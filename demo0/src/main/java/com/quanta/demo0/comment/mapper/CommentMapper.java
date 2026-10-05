package com.quanta.demo0.comment.mapper;

import com.github.pagehelper.Page;
import com.quanta.demo0.comment.dto.CommentAdminQueryDTO;
import com.quanta.demo0.comment.entity.CommentImage;
import com.quanta.demo0.comment.entity.ContentComment;
import com.quanta.demo0.comment.entity.ReplyCountRow;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * 评论表（tb_content_comment）数据访问接口。
 *
 * 简单 SQL 直接用注解，复杂查询（动态条件、窗口函数、批量 foreach）放在
 * resources/mapper/comment/CommentMapper.xml，方法名与 XML 的 id 一一对应。
 *
 * ============================================================
 * 【三种可见性口径并排，按调用方选】
 * ============================================================
 * - 用户查询口径：is_deleted = 0 AND audit_status = 1（XML 的 selectFirstLevelComments /
 *   selectReplyCountsByParentIds / selectTopRepliesByParentIds / selectByParentId，
 *   以及注解的 selectVisibleById）——待审(0)、驳回(2)一律不可见；
 * - 事实口径：只滤 is_deleted = 0（selectById）——供发评论/删除等写路径做存在性校验，
 *   待审评论也能查到，审核状态由调用方自行判断；
 * - 管理端口径：只滤 is_deleted = 0，audit_status 作为可选筛选（XML 的 pageAdmin）——
 *   管理员必须能看到待审/驳回数据。
 * **口径差异全在 SQL 的 WHERE 里，选错方法就会"看到不该看的"或"看不到该看的"**。
 */
@Mapper
public interface CommentMapper {

    /**
     * 根据父评论 ID
     * @param commentId
     * @return 评论对象
     *
     * 【口径】事实口径：只滤 is_deleted = 0、不过滤审核状态，供写路径校验
     * "评论是否还存在"（发评论时查父评论/被回复评论、删除时查评论本身）。
     * 查询展示请用可见口径的方法。
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
    // 【原子性】GREATEST(0, like_count + #{i})：MySQL 行内自增，并发点赞/取消互不覆盖；
    // GREATEST 把"取消比点赞多"的异常输入钳在 0，点赞数不为负。i 为 +1/-1
    @Update("update tb_content_comment set like_count = GREATEST(0, like_count + #{i}) where comment_id = #{commentId} and is_deleted = 0")
    int updateLikeCount(@Param("commentId") Long commentId,@Param("i") int i);


    /**
     * 查询一级评论
     * @param contentId
     * @param answerId
     * @param sortType
     * @return
     *
     * <p>XML 实现（selectFirstLevelComments）：parent_id IS NULL 即一级评论，
     * WHERE 带可见口径（is_deleted = 0 AND audit_status = 1）；配合 PageHelper 分页使用。
     * sortType=2 按点赞降序、再按时间降序，默认按时间降序。</p>
     */
    Page<ContentComment> selectFirstLevelComments(Long contentId, Long answerId, int sortType);

    /**
     * 查询回复评论数量
     * @param parentIds
     * @return
     *
     * <p>GROUP BY parent_id 一次聚合本页所有一楼的回复数，映射为 {@link ReplyCountRow}，
     * 供列表页一次查回复数、防 N+1（见 XML selectReplyCountsByParentIds）。</p>
     */
    List<ReplyCountRow> selectReplyCountsByParentIds(List<Long> parentIds);

    /**
     * 查询回复评论（3条）
     * @param parentIds
     * @param i
     * @return
     *
     * <p>XML 用 ROW_NUMBER() OVER (PARTITION BY parent_id ORDER BY create_time ASC)
     * 按楼分组编号，外层取 rn &lt;= i：每楼时间最早的前 i 条做内联预览
     * （调用方传 3；见 XML selectTopRepliesByParentIds）。</p>
     */
    List<ContentComment> selectTopRepliesByParentIds(List<Long> parentIds, int i);

   //软删除评论
   // XML 只置 is_deleted = 1，评论行保留——查询侧全带 is_deleted = 0 过滤，评论即"消失"
    void softDeleteById(Long commentId);

    //删除评论图片
    // 物理 DELETE（区别于评论本身的软删，tb_comment_image 的增删查均无 is_deleted 参与）
    @Delete("delete from tb_comment_image where comment_id = #{commentId}")
    void deleteCommentImages(Long commentId);

    /** 删除指定内容下评论图片并软删除评论；供内容域通过 CommentCommandService 调用。 */
    void deleteContentCommentImages(@Param("contentId") Long contentId);

    /** 软删除指定内容下全部评论；供内容删除事务调用。 */
    void softDeleteContentComment(@Param("contentId") Long contentId);

    //查询回复评论ID列表
    // 删一级评论前先收集回复 id，供批量软删回复与"评论数 -(1+回复数)"的联动扣减
    @Select("select comment_id from tb_content_comment where parent_id = #{commentId} and is_deleted = 0")
    List<Long> selectReplyIdsByParentId(Long commentId);

    //软删除回复评论
    void softDeleteRepliesByCommentIds(List<Long> replyCommentIds);

    //删除回复评论图片
    void deleteCommentImagesByCommentIds(List<Long> replyCommentIds);

    /**
     * 楼中楼分页：查某一级评论下的全部回复，配合 PageHelper 分页（XML selectByParentId）。
     *
     * <p>排序用 create_time + comment_id 双列（XML 内 choose 分支），**同一秒内发布的评论
     * 顺序也稳定，翻页不重不漏**；sortType=2 倒序，默认正序。可见口径同上。</p>
     */
    Page<ContentComment> selectByParentId(Long parentCommentId,int sortType);


    /**
     * 管理端分页查询评论列表（XML pageAdmin）。
     *
     * <p>只滤 is_deleted = 0，audit_status 是可选筛选——管理员要能看待审/驳回评论，
     * 与用户侧"只见过审评论"的口径不同。</p>
     */
    Page<ContentComment> pageAdmin(@Param("query") CommentAdminQueryDTO query);



    /**
     * 只有审核状态仍等于旧值时才更新，防止 AI 与管理员互相覆盖。
     *
     * <p>XML（updateAuditStatusIfCurrent）WHERE 里同时匹配 audit_status = #{oldAuditStatus}，
     * 相当于数据库侧 CAS：先到先得，后到的更新影响行数为 0、调用方按失败处理。</p>
     */
    int updateAuditStatusIfCurrent(@Param("commentId") Long commentId, @Param("oldAuditStatus") Integer oldAuditStatus,
                                   @Param("newAuditStatus") Integer newAuditStatus, @Param("rejectReason") String rejectReason,
                                   @Param("auditUserId") Long auditUserId);

    /**
     * 根据评论 ID 查询图片 URL 列表（图片审核兜底用）
     */
    List<String> selectImagesByCommentId(@Param("commentId") Long commentId);

    // 可见口径：audit_status = 1 且 is_deleted = 0，bot 回溯评论链专用（对比 selectById 的事实口径）
    @Select("select * from tb_content_comment where comment_id = #{commentId} and audit_status = 1 and is_deleted = 0")
    ContentComment selectVisibleById(Long commentId);

    // ===== bot 只读查询（BotCommentServiceImpl 专用）=====
    // 手工分页 limit #{offset}, #{limit} + 独立 count 语句；排序都带 comment_id 兜底，
    // 同一秒内发布的评论顺序稳定，翻页不重不漏。口径统一为可见口径（audit_status=1 且未删）。
    @Select("select * from tb_content_comment where user_id = #{userId} and content_id = #{postId} and audit_status = 1 and is_deleted = 0 order by create_time asc, comment_id asc limit #{offset}, #{limit}")
    List<ContentComment> selectBotHistory(@Param("userId") Long userId,
                                          @Param("postId") Long postId,
                                          @Param("offset") int offset,
                                          @Param("limit") int limit);

    // 与 selectBotHistory 完全同口径的 count，total 单独查、不为拿总数多拉数据
    @Select("select count(*) from tb_content_comment where user_id = #{userId} and content_id = #{postId} and audit_status = 1 and is_deleted = 0")
    long countBotHistory(@Param("userId") Long userId, @Param("postId") Long postId);

    // 帖子全部可见评论按时间升序（一楼和回复混排的"楼层流"，不区分层级）
    @Select("select * from tb_content_comment where content_id = #{postId} and audit_status = 1 and is_deleted = 0 order by create_time asc, comment_id asc limit #{offset}, #{limit}")
    List<ContentComment> selectBotFloorsAsc(@Param("postId") Long postId,
                                            @Param("offset") int offset,
                                            @Param("limit") int limit);

    // 同 selectBotFloorsAsc，改为时间降序（sortType="desc" 时走这里）
    @Select("select * from tb_content_comment where content_id = #{postId} and audit_status = 1 and is_deleted = 0 order by create_time desc, comment_id desc limit #{offset}, #{limit}")
    List<ContentComment> selectBotFloorsDesc(@Param("postId") Long postId,
                                             @Param("offset") int offset,
                                             @Param("limit") int limit);

    // 与 selectBotFloorsAsc/Desc 完全同口径的 count
    @Select("select count(*) from tb_content_comment where content_id = #{postId} and audit_status = 1 and is_deleted = 0")
    long countBotFloors(@Param("postId") Long postId);

    // 批量查评论图片：一次 IN 查询按 commentId 取回，供 bot 组装节点时防 N+1
    List<CommentImage> selectImagesByCommentIds(@Param("commentIds") List<Long> commentIds);
}
