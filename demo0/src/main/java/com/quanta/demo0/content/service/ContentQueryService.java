package com.quanta.demo0.content.service;

import com.quanta.demo0.content.vo.ContentSnapshotVO;
import com.quanta.demo0.content.vo.ContentVO;
import com.quanta.demo0.platform.common.enums.AuditStatus;
import com.quanta.demo0.platform.common.result.PageVO;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * 内容域查询服务。
 *
 * <p>这里仅暴露内容查询结果和稳定快照，调用方不需要依赖内容实体。</p>
 *
 * ============================================================
 * 【先分清两组方法：读模型（过滤过的"视图"） vs 事实（数据库当前行）】
 * ============================================================
 * 读模型组：getContentDetail、getContentSnapshots、getMy* 系列、pageUserPublicContents
 *   —— 只返回"已审核且未删除"的可见内容，服务 C 端页面；
 * 事实组：getContentSnapshot、getContentFactSnapshots、getContentFactImageUrls、
 *   lockContentSnapshot、*ForReindex 系列 —— **不过滤审核态/删除态**，
 *   服务写入校验、索引对账、派生索引重建（必须看见全部事实才能正确对账）。
 * 方法名里有没有 Fact 就是可见性过滤的开关——**跨域调用前先想清楚要哪一组**，
 * 拿事实组的数据直接上 C 端页面会泄露未审核内容。
 */
public interface ContentQueryService {

    /**
     * 帖子详情读模型：缓存快照 + 逐请求补齐访问者状态。
     *
     * 【给谁用】内容详情 Controller（C 端详情页）。
     * 【契约】NOT_FOUND / DELETED / NOT_APPROVED / 作者异常均抛 ContentFailedException；
     * 共享快照走两级缓存（见 ContentDetailCacheService），而"我是否点赞/收藏"与
     * 浏览历史**永远不进缓存**、每次单独计算——这是快照能被所有访问者共享的前提。
     */
    ContentVO getContentDetail(Long contentId);

    /**
     * 按 id 批量取可见内容快照（Feed/推荐列表装配用）。
     *
     * 【契约】入参不信任（去重、滤 null），一次 IN 查询防 N+1；
     * **返回条数可能少于请求条数**——已删/未审的会被静默过滤，
     * 调用方按"缺了就缺了"处理，不要假设返回数 == 请求数。
     */
    List<ContentSnapshotVO> getContentSnapshots(Collection<Long> contentIds);

    /** 查询当前数据库事实，不过滤审核态，供写入校验和索引校准使用。 */
    // 【契约】直查 MySQL 不走缓存；返回 null = 内容不存在（不存在 ≠ 不可见，调用方自行判定）。
    ContentSnapshotVO getContentSnapshot(Long contentId);

    /** 保留原 selectBatchIds 的已审核、未删除及正文摘要查询语义。 */
    // 【现状】实现不做任何可见性过滤（已删/未审照常返回），是 getContentSnapshots 的"事实版"，
    // 供对账/重建类调用方使用；上面的既有注释描述的是它从旧服务迁移时的历史来源。
    List<ContentSnapshotVO> getContentFactSnapshots(Collection<Long> contentIds);

    /** 在调用者事务中锁定内容事实，供回答采纳串行化使用。 */
    // 【契约】SELECT ... FOR UPDATE——**必须在调用方的事务内调用**才有意义，
    // 行锁持有到事务提交/回滚为止；并发的"采纳回答"在此串行化（见 AnswerCommandServiceImpl）。
    ContentSnapshotVO lockContentSnapshot(Long contentId);

    /** 分页返回所有状态的内容事实，供派生索引 upsert/delete 重建使用。 */
    // 【坑】offset 深分页只适合离线/低频任务；重建期间的数据变动由对账事件兜底。
    List<ContentSnapshotVO> getContentSnapshotsForReindex(int offset, int limit);

    /** 返回已审核内容的点赞排序候选。 */
    // 【给谁用】TrendingDataLoader 的热门兜底候选集（按 liked 倒序，只含可见内容）。
    List<ContentSnapshotVO> getTopLikedContentSnapshots(int limit);

    /** 统计用户公开内容数量。 */
    // 【口径】未删除且审核通过（见 ContentMapper.countUserPublicContents），用于主页 tab 计数。
    Integer countUserPublicContents(Long userId);

    /**
     * 分页查询已审核内容的稳定快照，供 RAG 全量重建使用。
     *
     * @param offset 偏移量
     * @param limit  批次大小
     * @return 已审核且未删除的内容快照
     */
    List<ContentSnapshotVO> getApprovedContentSnapshotsForReindex(int offset, int limit);

    /**
     * 按发布者取已审核内容快照（最多 limit 条，按创建时间倒序）。
     * 【给谁用】关注流回填（FollowFeedServiceImpl，一次最多拉 1000 条补全关注人的帖子）。
     */
    List<ContentSnapshotVO> getApprovedContentSnapshotsByAuthor(Long publishUserId, int limit);

    /**
     * 在固定时间与 ID 上界内按时间键集向前查询推荐候选。
     * 只返回审核通过且未删除的内容；before 游标为空表示从固定上界开始。
     */
    List<ContentSnapshotVO> getApprovedRecommendCandidates(
            Integer contentType,
            LocalDateTime upperTime,
            Long upperId,
            LocalDateTime beforeTime,
            Long beforeId,
            int limit);

    /** 为推荐会话固定创建时的内容 ID 上界，避免新帖插入改变本轮候选边界。 */
    Long getApprovedRecommendUpperId(Integer contentType, LocalDateTime upperTime);

    /** 取内容图片 URL 列表（过滤空 URL），供 Feed/搜索装配图片字段。 */
    List<String> getContentImageUrls(Long contentId);

    /** 返回图片事实序列，不过滤空 URL，供保留原列表装配契约的读取方使用。 */
    // 【与 getContentImageUrls 的差异】空 URL/重复项原样保留——需要看到图片表"真实事实"
    // （包括空槽位）的调用方用这个，展示场景用上面过滤版。
    List<String> getContentFactImageUrls(Long contentId);

    /**
     * 分页查询"我发布的内容"，可按审核状态过滤（null = 全部状态）。
     * 【契约】作者看自己的帖子不受可见性过滤（含待审/驳回，仅排除已删）——
     * 作者必须能看到自己帖子的审核进度，这是管理页"为什么驳回"的入口。
     */
    PageVO<ContentVO> getMyContentList(Long userId, Integer current, Integer size, AuditStatus auditStatus);

    /** 分页查询"我点赞的内容"（互动明细 join 内容装配）。 */
    PageVO<ContentVO> getMyLikedContentList(Long userId, Integer current, Integer size);

    /** 分页查询"我收藏的内容"。 */
    PageVO<ContentVO> getMyCollectContentList(Long userId, Integer current, Integer size);

    /**
     * 分页查询"我的浏览历史"。
     * 【坑】与其它分页不同：历史 id 先分页取出，再批量查内容并**过滤掉已删/未审**，
     * total 取的是过滤后的可见条数而非历史总条数——翻页总数会随可见性变化，属已知取舍。
     */
    PageVO<ContentVO> getMyBrowseHistoryContentList(Long userId, Integer current, Integer size);

    /** 分页查询某用户的公开内容（他人主页用；只含已审核且未删除）。 */
    PageVO<ContentVO> pageUserPublicContents(Long userId, Integer current, Integer size);
}
