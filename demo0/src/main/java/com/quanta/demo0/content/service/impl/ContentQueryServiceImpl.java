package com.quanta.demo0.content.service.impl;

import cn.hutool.core.util.BooleanUtil;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.quanta.demo0.content.entity.Content;
import com.quanta.demo0.content.entity.ContentImage;
import com.quanta.demo0.content.enums.ContentDetailState;
import com.quanta.demo0.content.exception.ContentFailedException;
import com.quanta.demo0.content.service.ContentDetailCacheService;
import com.quanta.demo0.content.service.ContentQueryService;
import com.quanta.demo0.content.vo.ContentDetailCacheEntry;
import com.quanta.demo0.content.vo.ContentDetailSnapshot;
import com.quanta.demo0.content.vo.ContentSnapshotVO;
import com.quanta.demo0.content.vo.ContentVO;
import com.quanta.demo0.interaction.service.BrowseHistoryService;
import com.quanta.demo0.interaction.service.ContentInteractionService;
import com.quanta.demo0.content.mapper.ContentMapper;
import com.quanta.demo0.user.service.UserQueryService;
import com.quanta.demo0.platform.common.enums.AuditStatus;
import com.quanta.demo0.platform.common.result.PageVO;
import com.quanta.demo0.platform.security.context.BaseContext;
import com.quanta.demo0.user.service.AuthorProfileCache;
import com.quanta.demo0.user.vo.UserAuthInfoVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 内容读模型服务。
 *
 * <p>提供稳定事实快照和访问者读模型；缓存仅保存稳定字段，互动状态逐请求补齐。</p>
 */
@Service
public class ContentQueryServiceImpl implements ContentQueryService {

    @Autowired
    private ContentMapper contentMapper;
    @Autowired
    private UserQueryService userQueryService;
    @Autowired
    private ContentInteractionService contentInteractionService;
    @Autowired
    private BrowseHistoryService browseHistoryService;
    @Autowired
    private ContentDetailCacheService contentDetailCacheService;
    @Autowired
    private ContentDetailDataLoader contentDetailDataLoader;
    @Autowired
    private AuthorProfileCache authorProfileCache;

    /**
     * 帖子详情 —— 读路径缓存的"组装车间"。
     *
     * ============================================================
     * 【方法结构本身就是分层缓存的教科书】
     * ============================================================
     * 第一段：getOrLoad 拿**共享快照**（L1 → L2 → loader，三级依次兜底）。
     *   快照里只有与访问者无关的数据，所以能被所有请求共享。
     *
     * 第二段：状态检查 NOT_FOUND/DELETED/NOT_APPROVED → 抛业务异常。
     *   注意这些"负结果"在 CacheService 里已被短 TTL 缓存 ——
     *   爬虫反复戳已删帖时，DB 完全不受影响（防穿透的落点就在这）。
     *
     * 第三段：**访问者状态永远不进缓存**，每次单独算：
     *   markLiked / markCollected 查"我"的互动记录，recordBrowseHistory 写"我"的浏览历史。
     *   【面试高频】为什么浏览历史是同步写而不发 MQ？
     *   —— 它影响用户自己的下一屏体验（"已读"变灰），丢失可容忍但延迟不可容忍，
     *   且是单行 insert，代价小。判断标准还是那句：这个数据要"本次请求立即可见"吗？
     *
     * 【面试追问：作者信息为什么走 AuthorProfileCache 而不是放详情快照里？】
     *   —— 作者昵称/头像改了，他名下几百个帖子的详情快照不可能逐个失效。
     *   作者信息独立成"作者维度"的缓存（AuthorProfileCache，本地批量），
     *   改资料只失效作者自己那条 —— **缓存的粒度 = 失效的粒度**，粒度错了失效就是灾难。
     */
    @Override
    public ContentVO getContentDetail(Long contentId) {
        if (contentId == null) {
            throw new ContentFailedException("contentId不能为空");
        }
        // 读缓存：L1 → L2 → loader 回源，返回的 entry 可能是负状态（NOT_FOUND/DELETED 等）
        ContentDetailCacheEntry cacheEntry = contentDetailCacheService.getOrLoad(
                contentId,
                () -> contentDetailDataLoader.load(contentId)
        );
        if (cacheEntry == null || cacheEntry.state() == null) {
            throw new ContentFailedException("内容不存在");
        }
        if (cacheEntry.state() == ContentDetailState.NOT_FOUND) {
            throw new ContentFailedException("内容不存在");
        }
        if (cacheEntry.state() == ContentDetailState.DELETED) {
            throw new ContentFailedException("内容已被删除");
        }
        if (cacheEntry.state() == ContentDetailState.NOT_APPROVED) {
            throw new ContentFailedException("内容未通过审核");
        }
        if (cacheEntry.state() == ContentDetailState.INVALID_AUTHOR || cacheEntry.snapshot() == null) {
            throw new ContentFailedException("发布用户信息异常");
        }

        ContentDetailSnapshot snapshot = cacheEntry.snapshot();// 从缓存里取快照,用来组装VO
        UserAuthInfoVO userInfo = authorProfileCache.get(snapshot.publishUserId());
        // 作者资料缺失时给空对象兜底：帖子仍可浏览，只是昵称/头像为空 —— 作者数据异常不该拖垮内容阅读
        if (userInfo == null) {
            userInfo = new UserAuthInfoVO();
        }
        // 计算访问者状态：点赞/收藏/浏览历史
        Content viewerState = Content.builder().contentId(snapshot.contentId()).build();
        markLiked(viewerState);// 计算点赞状态
        markCollected(viewerState);// 计算收藏状态
        recordBrowseHistory(contentId);// 记录浏览历史
        return convertDetailSnapshotToVO(snapshot, userInfo, viewerState);// 组装VO
    }

    /**
     * 批量快照查询（供 Feed 等场景按 id 列表取内容）。
     *
     * 【三个防御性细节，都是真实事故形状】
     * 1. distinct + filter null：调用方（Feed 装配）传来的 id 来自 ZSET 成员，
     *    理论上不会有重复/空，但契约上不信任上游 —— 批量接口是最容易被"脏入参"打穿的。
     * 2. selectBatchIds 一次 IN 查询 —— 防 N+1（循环单查是面试必挂项）。
     * 3. toMap 用 (left, right) -> left 处理重复 key：MySQL IN 理论不返回重复行，
     *    但 toMap 默认遇到重复 key 直接抛 IllegalStateException ——
     *    **Collection → Map 的合并函数不是可选项，是保险丝**。
     *
     * 【为什么过滤 isDeleted / 非 APPROVED 放内存而不是拼进 SQL？】
     * 数据都在内存里了，过滤是 O(n)；拼进 SQL 也可以，这里纯属风格。
     * 真正的原则是：**批量接口返回"请求了但被过滤掉"是正常的**（Feed 里帖子
     * 被删是常态），调用方按"返回条数 ≤ 请求条数"处理即可。
     */
    @Override
    public List<ContentSnapshotVO> getContentSnapshots(Collection<Long> contentIds) {
        if (contentIds == null || contentIds.isEmpty()) {
            return Collections.emptyList();
        }
        List<Long> ids = contentIds.stream()
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (ids.isEmpty()) {
            return Collections.emptyList();
        }
        List<Content> contents = contentMapper.selectBatchIds(ids);
        if (contents == null || contents.isEmpty()) {
            return Collections.emptyList();
        }
        Map<Long, Content> byId = contents.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(Content::getContentId, Function.identity(), (left, right) -> left));
        return ids.stream()
                .map(byId::get)
                .filter(Objects::nonNull)
                .filter(content -> !Integer.valueOf(1).equals(content.getIsDeleted()))
                .filter(content -> AuditStatus.APPROVED.getCode().equals(content.getAuditStatus()))
                .map(this::toSnapshot)
                .toList();
    }

    /**
     * 单个事实快照：直查 DB、不走详情缓存 —— 跨域做"存在性/状态判断"要用当前事实，
     * 不能用可能陈旧的缓存投影（调用方：answer/comment 的写前校验、search 的装配等）。
     */
    @Override
    public ContentSnapshotVO getContentSnapshot(Long contentId) {
        Content content = contentMapper.selectById(contentId);
        return content == null ? null : toSnapshot(content);
    }

    /**
     * 事实快照（Fact = 不做任何可见性过滤）：已删/未审的行也原样返回。
     * 与 getContentSnapshots 是一对刻意分叉的口径：那个给 Feed 出"可见集"，
     * 这个给搜索对账 / RAG / 重排出"全量事实" —— 对账必须能看到被删的行才能校准索引，
     * 见 ContentSnapshotVO 类注释。
     */
    @Override
    public List<ContentSnapshotVO> getContentFactSnapshots(Collection<Long> contentIds) {
        if (contentIds == null || contentIds.isEmpty()) {
            return List.of();
        }
        List<Content> contents = contentMapper.selectBatchIds(new ArrayList<>(contentIds));
        return contents == null ? List.of() : contents.stream().map(this::toSnapshot).toList();
    }

    /** FOR UPDATE 悲观锁版本：必须在调用方事务内使用（SQL 见 ContentMapper.selectByIdForUpdate），当前用于创建回答前锁住问题行。 */
    @Override
    public ContentSnapshotVO lockContentSnapshot(Long contentId) {
        Content content = contentMapper.selectByIdForUpdate(contentId);
        return content == null ? null : toSnapshot(content);
    }

    /** 全量重建索引用：不过滤删除/审核状态 —— 重建器要"全集"才能把索引里多余的行清掉（对照见 ContentMapper.selectAllForReindex 的说明）。 */
    @Override
    public List<ContentSnapshotVO> getContentSnapshotsForReindex(int offset, int limit) {
        List<Content> contents = contentMapper.selectAllForReindex(offset, limit);
        return contents == null ? List.of() : contents.stream().map(this::toSnapshot).toList();
    }

    /** 热门兜底：按 liked 倒序取存活且过审的前 N 条（供热榜等场景在缓存/ES 缺数据时兜底，见 ContentMapper.selectTopLikedContents）。 */
    @Override
    public List<ContentSnapshotVO> getTopLikedContentSnapshots(int limit) {
        List<Content> contents = contentMapper.selectTopLikedContents(limit);
        return contents == null ? List.of() : contents.stream().map(this::toSnapshot).toList();
    }

    /** 公开主页帖子数：口径 = 未删除且审核通过（见 ContentMapper.countUserPublicContents）。 */
    @Override
    public Integer countUserPublicContents(Long userId) {
        return contentMapper.countUserPublicContents(userId);
    }

    /** 推荐预热用：Mapper 侧只取过审行，内存里再对 isDeleted/审核状态做一遍双保险过滤（过滤放内存的原因见 getContentSnapshots 的注释）。 */
    @Override
    public List<ContentSnapshotVO> getApprovedContentSnapshotsForReindex(int offset, int limit) {
        List<Content> contents = contentMapper.selectApprovedForRecommendWarmup(offset, limit);
        if (contents == null || contents.isEmpty()) {
            return List.of();
        }
        return contents.stream()
                .filter(Objects::nonNull)
                .filter(content -> Integer.valueOf(0).equals(content.getIsDeleted()))
                .filter(content -> AuditStatus.APPROVED.getCode().equals(content.getAuditStatus()))
                .map(this::toSnapshot)
                .toList();
    }

    /** 关注 Feed 回填作者近期帖子用（调用方见 FollowFeedServiceImpl），只取审核通过的公开行。 */
    @Override
    public List<ContentSnapshotVO> getApprovedContentSnapshotsByAuthor(Long publishUserId, int limit) {
        List<Content> contents = contentMapper.selectApprovedByPublishUserId(publishUserId, limit);
        if (contents == null || contents.isEmpty()) {
            return List.of();
        }
        return contents.stream().filter(Objects::nonNull).map(this::toSnapshot).toList();
    }

    /**
     * 推荐扩召回查询：由内容域 Mapper 按固定时间键集分页，返回前再次确认公开状态。
     * 查询异常向上抛出，调用方不能把数据库故障误报成候选耗尽。
     */
    @Override
    public List<ContentSnapshotVO> getApprovedRecommendCandidates(
            Integer contentType,
            LocalDateTime upperTime,
            Long upperId,
            LocalDateTime beforeTime,
            Long beforeId,
            int limit) {
        if (limit <= 0 || upperTime == null || upperId == null || upperId <= 0) {
            return List.of();
        }
        List<Content> contents = contentMapper.getApprovedRecommendCandidates(
                contentType, upperTime, upperId, beforeTime, beforeId, limit);
        if (contents == null || contents.isEmpty()) {
            return List.of();
        }
        return contents.stream()
                .filter(Objects::nonNull)
                .filter(content -> Integer.valueOf(0).equals(content.getIsDeleted()))
                .filter(content -> AuditStatus.APPROVED.getCode().equals(content.getAuditStatus()))
                .map(this::toSnapshot)
                .toList();
    }

    /** 推荐会话上界查询：无可见内容时统一返回 0，便于会话服务判定空源。 */
    @Override
    public Long getApprovedRecommendUpperId(Integer contentType, LocalDateTime upperTime) {
        if (upperTime == null) {
            return 0L;
        }
        Long upperId = contentMapper.getApprovedRecommendUpperId(contentType, upperTime);
        return upperId == null ? 0L : upperId;
    }

    /** 详情页/装配用图片列表：与 ContentDetailDataLoader 同口径（过滤空白 URL）。 */
    @Override
    public List<String> getContentImageUrls(Long contentId) {
        List<ContentImage> images = contentMapper.selectImagesByContentIds(contentId);
        if (images == null || images.isEmpty()) {
            return List.of();
        }
        return images.stream()
                .map(ContentImage::getImageUrl)
                .filter(org.apache.commons.lang3.StringUtils::isNotBlank)
                .toList();
    }

    /** 保留原 Mapper 图片投影的顺序、重复项和空值，不复用审核用的 URL 过滤规则。 */
    @Override
    public List<String> getContentFactImageUrls(Long contentId) {
        List<ContentImage> images = contentMapper.selectImagesByContentIds(contentId);
        if (images == null || images.isEmpty()) {
            return List.of();
        }
        return images.stream().map(ContentImage::getImageUrl)
                .collect(java.util.stream.Collectors.toList());
    }

    private ContentSnapshotVO toSnapshot(Content content) {
        return ContentSnapshotVO.builder()
                .contentId(content.getContentId())
                .contentType(content.getContentType())
                .title(content.getTitle())
                .content(content.getContent())
                .tags(content.getTags())
                .publishUserId(content.getPublishUserId())
                .auditStatus(content.getAuditStatus())
                .isDeleted(content.getIsDeleted())
                .createTime(content.getCreateTime())
                .updateTime(content.getUpdateTime())
                .likedCount(content.getLiked())
                .commentCount(content.getCommentCount())
                .collectCount(content.getCollectCount())
                .build();
    }

    /** 我的帖子列表：分页骨架见 pageQuery；SQL 不过滤审核状态是特性 —— 用户中心要能看到自己的待审/被驳回帖（见 ContentMapper.getMyContentsList）。 */
    @Override
    public PageVO<ContentVO> getMyContentList(Long userId, Integer current, Integer size, AuditStatus auditStatus) {
        return pageQuery(userId, current, size,
                () -> contentMapper.getMyContentsList(userId, auditStatus == null ? null : auditStatus.getCode()));
    }

    /** 我点赞过的帖子：SQL 先按 user_id 走互动表索引拿 content_id 集合再回主表（见 ContentMapper.getMyLikedContentList）。 */
    @Override
    public PageVO<ContentVO> getMyLikedContentList(Long userId, Integer current, Integer size) {
        return pageQuery(userId, current, size, () -> contentMapper.getMyLikedContentList(userId));
    }

    /** 我收藏过的帖子：结构同 getMyLikedContentList，互动表换成 tb_content_collect。 */
    @Override
    public PageVO<ContentVO> getMyCollectContentList(Long userId, Integer current, Integer size) {
        return pageQuery(userId, current, size, () -> contentMapper.getMyCollectContentList(userId));
    }

    /**
     * 浏览历史分页 —— 唯一一个不走 pageQuery 的列表方法，因为**分页发生在"历史 id 列表"上
     * 而不是内容表上**：先从浏览历史表按时间倒序分页取 contentId（Page<Long>），
     * 再拿这页 id 批量查内容、内存里过滤掉已删/未审的行。
     *
     * 【注意 total 的口径】这里的 total 是"当前页过滤后仍可见的条数"，不是历史总数 ——
     * 要精确 total 得对整个历史做可见性过滤统计，为一个人的列表再扫全表不值，
     * 所以选了最便宜的口径。代价是 hasMore 只是粗略信号，读这段代码别用
     * pageQuery 的常规分页语义去套（对照学习点：两种分页骨架的取舍）。
     */
    @Override
    public PageVO<ContentVO> getMyBrowseHistoryContentList(Long userId, Integer current, Integer size) {
        int pageNum = current == null || current <= 0 ? 1 : current;
        int pageSize = size == null || size <= 0 ? 10 : size;
        Page<Long> page = browseHistoryService.pageContentIds(userId, pageNum, pageSize);
        List<Long> ids = page == null || page.getResult() == null ? List.of() : page.getResult();
        if (ids.isEmpty()) {
            return PageVO.<ContentVO>builder()
                    .list(new ArrayList<>())
                    .pageSize(pageSize)
                    .pageNum(pageNum)
                    .totalPage(0)
                    .total(0L)
                    .hasMore(false)
                    .build();
        }
        List<Content> visible = contentMapper.selectBatchIds(ids).stream()
                .filter(content -> Integer.valueOf(0).equals(content.getIsDeleted()))
                .filter(content -> AuditStatus.APPROVED.getCode().equals(content.getAuditStatus()))
                .toList();
        List<ContentVO> voList = assembleContentVOs(visible);
        long total = visible.size();
        int totalPages = (int) Math.ceil(total / (double) pageSize);
        return PageVO.<ContentVO>builder()
                .list(voList)
                .pageSize(pageSize)
                .pageNum(pageNum)
                .totalPage(totalPages)
                .total(total)
                .hasMore(pageNum < totalPages)
                .build();
    }

    @Override
    public PageVO<ContentVO> pageUserPublicContents(Long userId, Integer current, Integer size) {
        return pageQuery(userId, current, size, () -> contentMapper.pageUserPublicContents(userId));
    }

    /**
     * 三个"我的列表"共用的分页骨架：入参校验 → PageHelper 分页 → 组装 VO → 统一 PageVO 形状。
     * 查询本身用 Supplier 传进来 —— 骨架只管"怎么分页"，各方法只声明"查什么"。
     *
     * 【PageHelper 的坑：startPage 和查询必须紧贴】
     * startPage 把分页参数放进 ThreadLocal，**下一条**执行的查询会被改写成 LIMIT ——
     * 中间若插入了别的 SQL，分页参数会错落到无辜的查询上。所以这里 query.get()
     * 紧跟 startPage，中间只有参数归一化（current/size 非法时兜底为 1/10）。
     *
     * 【total 与 totalPages】total 由 PageHelper 自动发的 COUNT 语句拿到；
     * totalPages 用 Math.ceil 手算 —— 典型的"余数也算一页"（5 条、每页 10 → 1 页而非 0 页）。
     */
    private PageVO<ContentVO> pageQuery(Long userId, Integer current, Integer size,
                                        java.util.function.Supplier<Page<Content>> query) {
        if (userId == null) {
            throw new ContentFailedException("userId不能为空");
        }
        int pageNum = current == null || current <= 0 ? 1 : current;
        int pageSize = size == null || size <= 0 ? 10 : size;
        PageHelper.startPage(pageNum, pageSize);
        Page<Content> page = query.get();
        List<Content> contentList = page == null || page.getResult() == null
                ? new ArrayList<>() : page.getResult();
        List<ContentVO> voList = assembleContentVOs(contentList);
        long total = page == null ? 0L : page.getTotal();
        int totalPages = (int) Math.ceil(total / (double) pageSize);
        return PageVO.<ContentVO>builder()
                .list(voList)
                .pageSize(pageSize)
                .pageNum(pageNum)
                .totalPage(totalPages)
                .total(total)
                .hasMore(pageNum < totalPages)
                .build();
    }

    /**
     * 列表页装配：内容批查完成后，补作者信息和访问者互动状态。
     *
     * 【防 N+1 的两段对照】
     * 作者信息：先收集去重所有 publishUserId，再 getUserAuthInfos 一次 IN 查询 ——
     * 批量永远是正解（对照 getContentSnapshots 里的 selectBatchIds，同一思想）。
     * 互动状态：forEach 里逐帖调 markLiked/markCollected —— 每帖 2 次点查，
     * N 帖 = 2N 次。没批量化是明确的代价点：列表页小而互动服务轻时无感，
     * 帖子数上去后应换成"一次查我这页所有帖子的互动记录"的批量接口 ——
     * 知道它慢在哪，比假装它不慢重要。
     */
    private List<ContentVO> assembleContentVOs(List<Content> contents) {
        if (contents == null || contents.isEmpty()) {
            return new ArrayList<>();
        }
        List<Long> userIds = contents.stream()
                .map(Content::getPublishUserId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        List<UserAuthInfoVO> users = userIds.isEmpty()
                ? new ArrayList<>() : userQueryService.getUserAuthInfos(userIds);
        Map<Long, UserAuthInfoVO> userMap = users == null ? Collections.emptyMap() : users.stream()
                .collect(Collectors.toMap(UserAuthInfoVO::getUserId, Function.identity(), (left, right) -> left));
        contents.forEach(this::markLiked);
        contents.forEach(this::markCollected);
        return contents.stream()
                .map(content -> convertContentToVO(content,
                        userMap.getOrDefault(content.getPublishUserId(), new UserAuthInfoVO())))
                .toList();
    }

    private ContentVO convertContentToVO(Content content, UserAuthInfoVO userInfo) {
        return ContentVO.builder()
                .contentId(content.getContentId())
                .contentType(content.getContentType())
                .title(content.getTitle())
                .content(content.getContent())
                .publishUserId(content.getPublishUserId())
                .auditStatus(content.getAuditStatus())
                .createTime(content.getCreateTime())
                .liked(content.getLiked() == null ? 0 : content.getLiked())
                .isLiked(BooleanUtil.isTrue(content.getIsLiked()))
                .isCollected(BooleanUtil.isTrue(content.getIsCollected()))
                .commentCount(content.getCommentCount() == null ? 0 : content.getCommentCount())
                .collectCount(content.getCollectCount() == null ? 0 : content.getCollectCount())
                .avatarUrl(userInfo.getAvatarUrl())
                .nickName(userInfo.getNickName())
                .quantaDepartment(userInfo.getQuantaDepartment())
                .quantaBatch(userInfo.getQuantaBatch())
                .build();
    }

    private ContentVO convertDetailSnapshotToVO(ContentDetailSnapshot snapshot,
                                                 UserAuthInfoVO userInfo,
                                                 Content viewerState) {
        return ContentVO.builder()
                .contentId(snapshot.contentId())
                .contentType(snapshot.contentType())
                .title(snapshot.title())
                .content(snapshot.content())
                .liked(snapshot.liked())
                .commentCount(snapshot.commentCount())
                .collectCount(snapshot.collectCount())
                .publishUserId(snapshot.publishUserId())
                .avatarUrl(userInfo.getAvatarUrl())
                .nickName(userInfo.getNickName())
                .quantaDepartment(userInfo.getQuantaDepartment())
                .quantaBatch(userInfo.getQuantaBatch())
                .auditStatus(snapshot.auditStatus())
                .createTime(snapshot.createTime())
                .images(snapshot.images())
                .isLiked(BooleanUtil.isTrue(viewerState.getIsLiked()))
                .isCollected(BooleanUtil.isTrue(viewerState.getIsCollected()))
                .build();
    }

    // 计算访问者点赞状态
    private void markLiked(Content content) {
        Long userId = BaseContext.getCurrentId();
        if (userId == null) {
            content.setIsLiked(false);
            return;
        }
        content.setIsLiked(contentInteractionService.isContentLiked(content.getContentId(), userId));
    }

    private void markCollected(Content content) {
        Long userId = BaseContext.getCurrentId();
        if (userId == null) {
            content.setIsCollected(false);
            return;
        }
        content.setIsCollected(contentInteractionService.isContentCollected(content.getContentId(), userId));
    }

    private void recordBrowseHistory(Long contentId) {
        browseHistoryService.recordBrowseHistory(contentId);
    }
}
