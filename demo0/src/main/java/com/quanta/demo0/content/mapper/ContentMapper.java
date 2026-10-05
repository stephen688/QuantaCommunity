package com.quanta.demo0.content.mapper;

import com.github.pagehelper.Page;
import com.quanta.demo0.content.dto.ContentAdminQueryDTO;
import org.apache.ibatis.annotations.*;

import java.time.LocalDateTime;
import java.util.List;

import com.quanta.demo0.content.entity.Content;
import com.quanta.demo0.content.entity.ContentImage;

/**
 * 帖子主表（tb_content）与图片表（tb_content_image）的数据访问层。
 *
 * SQL 分两处：简单语句用注解内联（@Select/@Update/@Delete），多行动态 SQL 在
 * src/main/resources/mapper/content/ContentMapper.xml —— 两者按 namespace 绑定到本接口，
 * 所以读每个方法的真实语义时必须对照 XML 看，接口上往往只有"一半"信息。
 *
 * ============================================================
 * 【"可见"的统一口径：is_deleted = 0 AND audit_status = 1】
 * ============================================================
 * 对外查询（selectById、selectBatchIds、pageUserPublicContents、selectTopLikedContents…）
 * 几乎都重复这两个条件。这不是忘了抽公共 fragment：
 * 管理端（pageAdmin、getMyContentsList）恰恰需要看见未审核/被驳回的行，
 * 可见性条件按场景各写各的反而清楚 —— **跟 DRY 比起来，"每处口径都明确"更重要**。
 *
 * 【计数为什么用 delta 参数（+1/-1）而不是读出来加一再写回？】
 * updateLiked / updateCollectCount / updateCommentCount 都是
 * `set xxx = GREATEST(0, xxx + #{i})`：自增在 MySQL 行内原子完成，
 * 并发的点赞/取消不会互相覆盖；GREATEST 把"取消比点赞多"的异常输入钳在 0，
 * 计数永不出现负数。
 *
 * 【返回 Page<Content> 的方法走 PageHelper】
 * 这些方法对应的 XML 里没有 LIMIT：PageHelper 拦截器根据 ThreadLocal 里的分页参数
 * 自动改写 SQL 并回填 Page 对象（调用方 ContentQueryServiceImpl 先 startPage 再查询）。
 */
@Mapper
public interface ContentMapper {


    /**
     * 插入内容
     * @param content
     *
     * 对应 XML 的 useGeneratedKeys=true：自增主键回填到 content.contentId，
     * 发布事务里随后的 batchInsertImages、Outbox 登记都依赖这个回填值。
     */
    void insert(Content content);

   /**
    * 批量插入内容图片
    * @param images
    *
    * XML 用 foreach 拼一条多 values 的 INSERT：一条 SQL 原子写完全部图片，
    * 与主帖 insert 在同一个发布事务里提交（见 ContentImage 类注释）。
    */
    void batchInsertImages(@Param("images") List<ContentImage> images);

    /**
     * 根据内容 ID 列表查询对应的图片列表
     * @param contentId 内容 ID 列表
     * @return 图片列表
     *
     * 【注意】注解 SQL 实际只支持单个 contentId 等值查询（方法名里的 s 指"该帖的多张图"），
     * 且没带 ORDER BY：返回顺序不保证按 sort，展示顺序应以 sort 字段为准。
     */
    @Select("select * from tb_content_image where content_id = #{contentId}")
    List<ContentImage> selectImagesByContentIds(Long contentId);

    /**
     * 按内容 ID 批量查询图片。
     *
     * XML 使用参数绑定的 IN 查询，并按 content_id、sort 排序，供内容列表装配
     * 一次取回本批次所有图片，避免逐条查询产生 N+1。
     *
     * @param contentIds 内容 ID 列表，调用方负责过滤 null 和空列表
     * @return 按内容 ID、图片 sort 排序的图片行
     */
    List<ContentImage> selectImagesBatchByContentIds(@Param("contentIds") List<Long> contentIds);

    /**
     * 根据内容 IDs 列表查询对应的内容列表
     * @param ids 内容 ID 列表
     * @return 内容列表
     *
     * 对应 XML 有三个要点：
     * ① 只查可见帖（is_deleted=0 且 audit_status=1），不可见 id 直接从结果里消失；
     * ② 正文被截成 100 字摘要（IF(CHAR_LENGTH>100, CONCAT(LEFT(…), '…'), …)) ——
     *    列表场景不需要全文，别把大字段白白搬出数据库；
     * ③ ORDER BY FIELD(content_id, …) 按**入参顺序**返回 —— feed/推荐按召回分排序后
     *    传 id 进来，SQL 不打乱这个顺序（换成普通排序就得在 Java 里重排一次）。
     * 一次 IN 查询替代循环单查，防 N+1。
     */
    List<Content> selectBatchIds( List<Long> ids);

    /**
     * 根据内容 ID 查询内容详情
     * @param contentId 内容 ID
     * @return 内容详情
     *
     * 只过滤 is_deleted=0、不过滤审核状态 —— 详情 loader 还要区分 DELETED 与
     * NOT_APPROVED 两种负结果（见 ContentDetailDataLoader），所以这里不能把
     * 未审核行提前滤掉。
     */
    @Select("select * from tb_content where content_id = #{contentId} and is_deleted = 0")
    Content selectById(Long contentId);

    /**
     * 根据内容 ID 查询内容详情（加锁）
     * @param contentId 内容 ID
     * @return 内容详情
     *
     * FOR UPDATE 悲观行锁，必须在调用方事务内使用（如 AnswerCommandServiceImpl
     * 创建回答前经 lockContentSnapshot 锁住问题行）：把"读帖 → 校验 → 写"这段
     * 临界区串行化。锁内不要做远程调用/模型调用，拿完快照尽快提交。
     */
    @Select("select * from tb_content where content_id = #{contentId} and is_deleted = 0 for update")
    Content selectByIdForUpdate(Long contentId);

    /**
     * 增减点赞数：i 传 +1/-1，XML 里 GREATEST(0, liked + #{i}) 行内原子自增并防负数，
     * 同时刷新 update_time —— 计数变化也会触发下游增量同步。
     * @return 是否更新到行（内容不存在/已删时为 false）
     */
    boolean updateLiked(@Param("contentId") Long contentId, @Param("i") int i);

    /**
     * 根据内容 ID 和用户 ID 删除点赞记录
     * @param contentId 内容 ID
     * @param userId 用户 ID
     */




    /**
     * 我的帖子列表：按作者查未删除帖，auditStatus 可选过滤，PageHelper 分页。
     * 【注意】XML 不过滤审核状态是特性 —— 用户中心要能看到自己的待审/被驳回帖。
     */
    Page<Content> getMyContentsList(@Param("userId") Long userId, @Param("auditStatus") Integer auditStatus);

    /**
     * 软删主帖：置 is_deleted=1 并刷新 update_time（触发下游同步）；
     * WHERE is_deleted=0 保证幂等，重复删除影响 0 行。对应 XML softDeleteContent。
     */
    void softDeleteContent(Long contentId);

    /**
     * 图片物理 DELETE（与主帖软删不同，理由见 ContentImage 类注释）；
     * 管理端删帖流程先删图再软删主帖（AdminContentServiceImpl）。
     */
    @Delete("delete from tb_content_image where content_id = #{contentId}")
    void deleteContentImages(Long contentId);



    /**
     * 我点赞过的帖子：先按 user_id 查互动表拿 content_id 集合，再回主表取可见帖，
     * PageHelper 分页（执行顺序依赖互动表 user_id 索引，互动量大时是性能关键点）。
     */
    @Select("select * from tb_content where content_id in (select content_id from tb_content_like where user_id = #{userId}) and is_deleted = 0 order by create_time desc")
    Page<Content> getMyLikedContentList(Long userId);

    /**
     * 我收藏过的帖子，结构同 getMyLikedContentList，只是互动表换成 tb_content_collect。
     */
    @Select("select * from tb_content where content_id in (select content_id from tb_content_collect where user_id = #{userId}) and is_deleted = 0 order by create_time desc")
    Page<Content> getMyCollectContentList(Long userId);

    /**
     * DB 端 LIKE 模糊检索（title/content 双列，可选按类型过滤）。
     * 主检索链路已迁到 search 包的 ES 索引（ContentIndexService.searchContent），
     * 本方法当前无 Java 调用方，作为数据库直查形态保留。
     * 【坑】LIKE '%kw%' 前置通配无法走普通索引，大表上等价于全表扫描。
     */
    Page<Content> searchContent(@Param("keyword") String keyword,@Param("contentType") Integer contentType);



    /**
     * 增减收藏数：语义同 updateLiked（delta + GREATEST 防负 + 刷新 update_time）。
     */
    int updateCollectCount(@Param("contentId") Long contentId, @Param("i") int i);

    /** 同步更新内容可见评论数。 */
    /**
     * 调用链：comment 域在评论可见性变化时经 ContentCounterServiceImpl 传 ±1；
     * GREATEST 防负同 updateLiked，is_deleted=0 限定只对存活帖计数。
     */
    @Update("update tb_content set comment_count = GREATEST(0, comment_count + #{i}) where content_id = #{contentId} and is_deleted = 0")
    int updateCommentCount(@Param("contentId") Long contentId, @Param("i") int i);

    /**
     * 全量按批捞帖（offset/limit 手工分页），供 ES 全量重建索引。
     * 刻意**不加** is_deleted/audit_status 过滤：search 包重建时可见行 upsert 进 ES、
     * 不可见行向 ES 发删除 —— 只有扫全表才能把"曾建过索引但现在不可见"的文档清干净
     * （见 ContentIndexServiceImpl.reindexAllFromMySql）。与下面只取可见行的
     * selectApprovedForRecommendWarmup 对照着记：**要不要过滤，取决于下游要全集还是可见集**。
     */
    @Select("select * from tb_content   order by create_time desc limit #{offset}, #{batchSize}")
    List<Content> selectAllForReindex(int offset, int batchSize);

    /**
     * 分页查询已通过且未删除的内容，用于推荐流 Redis 冷启动预热。
     */
    @Select("SELECT * FROM tb_content WHERE is_deleted = 0 AND audit_status = 1 ORDER BY create_time DESC LIMIT #{offset}, #{batchSize}")
    List<Content> selectApprovedForRecommendWarmup(@Param("offset") int offset, @Param("batchSize") int batchSize);


   
    /**
     * 管理端分页列表：is_deleted=0 + 可选审核状态/类型过滤，PageHelper 分页。
     * 管理端能看见待审帖，所以这里只有删除过滤（对照类注释的可见性口径）。
     */
    Page<Content> pageAdmin(@Param("query") ContentAdminQueryDTO query);

    /**
     * 动态部分更新（对应 XML 的 <set> + <if>）：非空字段才进 UPDATE。
     * 【注意】WHERE 只有 content_id，不校验删除/审核状态；update_time 也只在
     * 入参显式带上时才会刷新 —— 调用方自行保证目标行状态与水位线语义。
     */
    void update(Content updateContent);



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

    /** 推荐扩召回：按 create_time/content_id 复合键稳定向前扫描可见内容。 */
    List<Content> getApprovedRecommendCandidates(
            @Param("contentType") Integer contentType,
            @Param("upperTime") LocalDateTime upperTime,
            @Param("upperId") Long upperId,
            @Param("beforeTime") LocalDateTime beforeTime,
            @Param("beforeId") Long beforeId,
            @Param("limit") int limit);

    /** 兼容按 select 前缀命名的调用方，实际委托到推荐候选查询。 */
    default List<Content> selectApprovedRecommendCandidates(
            Integer contentType,
            LocalDateTime upperTime,
            Long upperId,
            LocalDateTime beforeTime,
            Long beforeId,
            int limit) {
        return getApprovedRecommendCandidates(contentType, upperTime, upperId, beforeTime, beforeId, limit);
    }

    /** 推荐会话创建时固定当前可见内容的最大 ID 上界。 */
    Long getApprovedRecommendUpperId(
            @Param("contentType") Integer contentType,
            @Param("upperTime") LocalDateTime upperTime);

    /** 兼容按 select 前缀命名的调用方，实际委托到推荐上界查询。 */
    default Long selectApprovedRecommendUpperId(Integer contentType, LocalDateTime upperTime) {
        return getApprovedRecommendUpperId(contentType, upperTime);
    }

    /**
     * 按内容 ID 游标查询审核通过且尚未完成主题标签处理的帖子。
     *
     * @param afterId 上一次批次最后一个内容 ID（不含）
     * @param limit 本批最大条数
     * @return 按内容 ID 升序排列的帖子
     */
    List<Content> selectApprovedWithoutTags(@Param("afterId") long afterId, @Param("limit") int limit);

    /**
     * 条件写入主题标签，只有可见内容且 tags 仍为 NULL 时才会成功。
     *
     * @param contentId 内容 ID
     * @param tags JSON 数组字符串
     * @return 实际更新行数
     */
    int updateTags(@Param("contentId") Long contentId, @Param("tags") String tags);


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
