package com.quanta.demo0.service.Impl;

import com.quanta.demo0.content.service.impl.ContentDetailDataLoader;
import com.quanta.demo0.interaction.mapper.BrowseHistoryMapper;
import com.quanta.demo0.search.service.impl.TrendingCacheInvalidator;
import cn.hutool.core.util.BooleanUtil;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.quanta.demo0.moderation.enums.ModerationTargetType;
import com.quanta.demo0.content.dto.ContentDTO;
import com.quanta.demo0.feed.dto.RecommendQueryDTO;
import com.quanta.demo0.search.dto.SearchDTO;
import com.quanta.demo0.platform.security.context.BaseContext;
import com.quanta.demo0.content.enums.ContentDetailState;
import com.quanta.demo0.search.es.service.ElasticSearchService;
import com.quanta.demo0.platform.common.enums.AuditStatus;
import com.quanta.demo0.content.exception.ContentFailedException;
import com.quanta.demo0.search.exception.SearchFailedException;
import com.quanta.demo0.search.mapper.SearchMapper;
import com.quanta.demo0.mapper.*;
import com.quanta.demo0.moderation.mq.message.ModerationTaskMessage;
import com.quanta.demo0.rag.vector.ContentVectorSyncService;
import com.quanta.demo0.platform.common.result.ScrollResult;
import com.quanta.demo0.moderation.mq.producer.ModerationProducer;
import com.quanta.demo0.moderation.properties.AliyunModerationProperties;
import com.quanta.demo0.content.service.ContentAuditService;
import com.quanta.demo0.user.service.AuthorProfileCache;
import com.quanta.demo0.content.service.ContentDetailCacheInvalidator;
import com.quanta.demo0.content.service.ContentDetailCacheService;
import com.quanta.demo0.content.service.ContentService;
import com.quanta.demo0.platform.mq.service.OutboxEventService;
import com.quanta.demo0.feed.service.RecommendRerankService;
import com.quanta.demo0.feed.utils.HotScoreCalculator;
import com.quanta.demo0.moderation.utils.SensitiveWordChecker;
import com.quanta.demo0.interaction.vo.CollectResultVO;
import com.quanta.demo0.content.vo.ContentVO;
import com.quanta.demo0.content.vo.ContentDetailCacheEntry;
import com.quanta.demo0.content.vo.ContentDetailSnapshot;
import com.quanta.demo0.interaction.vo.LikeResultVO;
import com.quanta.demo0.platform.common.result.PageVO;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;

import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.quanta.demo0.platform.redis.constant.RedisConstants.*;
import com.quanta.demo0.content.entity.Content;
import com.quanta.demo0.answer.entity.QuestionAnswer;
import com.quanta.demo0.content.entity.ContentImage;
import com.quanta.demo0.interaction.entity.BrowseHistory;
import com.quanta.demo0.user.vo.UserAuthInfoVO;
import com.quanta.demo0.search.entity.SearchHistory;
import com.quanta.demo0.notification.entity.Notification;

/**
 * 内容域核心服务实现类。
 *
 * 核心职责：
 * 1. 负责帖子发布、删除、点赞、收藏、推荐流查询等主业务流程；
 * 2. 协调内容审核、热度分更新、Feed 清理、搜索索引与向量索引同步；
 * 3. 处理内容举报、搜索落库等与内容生命周期相关的配套能力。
 *
 * 设计特点：
 * - 以事务保障关键写路径一致性，并通过 afterCommit 触发异步副作用；
 * - 对 Redis、MQ、ES、向量库等多组件进行编排，兼顾性能与可扩展性；
 * - 通过审核状态与内容类型约束，保证内容在各业务区的可见性规则。
 */
@Service
@Slf4j
public class ContentServiceImpl implements ContentService {

    @Autowired
    private ContentMapper contentMapper;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private QuestionMapper questionMapper;
    @Autowired
    private SensitiveWordChecker sensitiveWordChecker;
    @Autowired
    private SearchMapper searchMapper;
    @Autowired
    private ElasticSearchService elasticSearchService;
    @Autowired
    private ContentVectorSyncService contentVectorSyncService;
    @Autowired
    private BrowseHistoryMapper browseHistoryMapper;
    @Autowired
    private OutboxEventService outboxEventService;
    @Autowired
    private AliyunModerationProperties moderationProperties;
    @Autowired
    private ContentAuditService contentAuditService;
    @Autowired
    private TrendingCacheInvalidator trendingCacheInvalidator;
    @Autowired
    private ContentDetailCacheService contentDetailCacheService;
    @Autowired
    private ContentDetailDataLoader contentDetailDataLoader;
    @Autowired
    private AuthorProfileCache authorProfileCache;
    @Autowired
    private ContentDetailCacheInvalidator contentDetailCacheInvalidator;
    @Autowired
    private RecommendRerankService recommendRerankService;

    /**
     * 发布内容（帖子/回答）
     * 核心流程：
     * 1. 敏感词校验（标题+内容）→ 快速失败，避免无效数据入库
     * 2. 获取当前用户 ID（从 ThreadLocal 上下文）
     * 3. 参数校验（内容类型、标题长度、内容长度）
     * 4. 图片列表处理（过滤空字符串、限制最多 5 张）
     * 5. 插入内容表（初始审核状态为 PENDING）
     * 6. 批量插入图片表（关联 contentId）
     * 7. 事务提交后回调：根据配置发送 AI 审核任务或自动通过
     * 设计要点：
     * - 敏感词校验前置：避免无效数据写入数据库
     * - 事务包裹：保证内容和图片的原子性
     * - afterCommit 回调：确保事务成功后再发 MQ，避免消息丢失
     * - 审核开关控制：灵活切换 AI 审核/自动通过模式
     * 
     * @param contentDTO 发布内容请求参数
     * @return ContentVO 发布成功的内容信息
     */
    @Override
    @Transactional
    public ContentVO publish(ContentDTO contentDTO) {

        // 1. 敏感词校验（标题）
        String firstHit = sensitiveWordChecker.findFirstHit(contentDTO.getTitle());
        if (firstHit != null) {
            throw new ContentFailedException("标题包含敏感词：" + firstHit);
        }
        
        // 1. 敏感词校验（内容）
        firstHit = sensitiveWordChecker.findFirstHit(contentDTO.getContent());
        if (firstHit != null) {
            throw new ContentFailedException("内容包含敏感词：" + firstHit);
        }

        // 2. 获取发布用户 ID（从 ThreadLocal 上下文获取，由 JWT 拦截器设置）
        Long publishUserId = BaseContext.getCurrentId();
        
        // 3. 参数校验
        // 3.1 内容类型必须为 1（帖子）或 2（回答）
        if (contentDTO.getContentType() == null || (contentDTO.getContentType() != 1 && contentDTO.getContentType() != 2)) {
            throw new ContentFailedException("内容类型必须为 1 或者 2");
        }
        
        // 3.2 标题校验（非空 + 长度限制 50 字）
        if (StringUtils.isBlank(contentDTO.getTitle())) {
            throw new ContentFailedException("标题不能为空");
        }
        if (contentDTO.getTitle().length() > 50) {
            throw new ContentFailedException("标题不能超过 50 字");
        }
        
        // 3.3 内容校验（非空 + 长度限制 500 字）
        if (StringUtils.isBlank(contentDTO.getContent())) {
            throw new ContentFailedException("内容不能为空");
        }
        if (contentDTO.getContent().length() > 500) {
            throw new ContentFailedException("内容不能超过 500 字");
        }

        // 4. 图片列表处理（过滤空字符串、限制最多 5 张）
        List<String> images = contentDTO.getImages() == null ? new ArrayList<>() :
                contentDTO.getImages().stream()
                        .filter(StringUtils::isNotBlank)
                        .toList();
        if (images.size() > 5) {
            throw new ContentFailedException("图片列表最多 5 张");
        }

        // 5. 插入内容表（初始审核状态为 PENDING）
        Content content = Content.builder()
                .contentType(contentDTO.getContentType())
                .title(contentDTO.getTitle())
                .content(contentDTO.getContent())
                .publishUserId(publishUserId)
                .auditStatus(AuditStatus.PENDING.getCode())  // 待审核状态
                .createTime(LocalDateTime.now())
                .updateTime(LocalDateTime.now())
                .isLiked(false)
                .isCollected(false)
                .liked(0)
                .commentCount(0)
                .collectCount(0)
                .isDeleted(0)
                .build();
        contentMapper.insert(content);

        // 5.1 校验 contentId 是否成功获取（MyBatis 自增主键回填）
        if (content.getContentId() == null) {
            throw new ContentFailedException("内容发布失败，请稍后重试");
        }

        // 6. 批量插入图片表（关联 contentId，sort 字段保证图片顺序）
        if (!images.isEmpty()) {
            List<ContentImage> contentImages = new ArrayList<>();
            for (int i = 0; i < images.size(); i++) {
                ContentImage contentImage = ContentImage.builder()
                        .contentId(content.getContentId())
                        .imageUrl(images.get(i))
                        .sort(i)  // 排序字段，前端按此顺序展示
                        .createTime(LocalDateTime.now())
                        .build();
                contentImages.add(contentImage);
            }
            contentMapper.batchInsertImages(contentImages);
        }
        
        // 7. 事务提交后回调：发送 AI 审核任务或按配置自动通过
        schedulePostPublishActions(content, images);
        ContentVO contentVO = ContentVO.builder()
                .contentId(content.getContentId())
                .contentType(content.getContentType())
                .title(content.getTitle())
                .content(content.getContent())
                .publishUserId(content.getPublishUserId())
                .auditStatus(content.getAuditStatus())
                .createTime(content.getCreateTime())
                .images(images)
                .liked(content.getLiked())
                .commentCount(content.getCommentCount())
                .collectCount(0)
                .isLiked(false)
                .isCollected(false)
                .build();

        contentDetailCacheInvalidator.evictAfterCommit(content.getContentId(), "content-publish");
        return contentVO;

    }


    /**
     * 发布后处理：根据配置发送 AI 审核任务或自动通过
     * 步骤：
     * 1. 检查是否需要审核内容
     * 2. 如果需要审核，发送 AI 审核任务
     * 3. 如果不需要审核，自动通过
     * @param content
     * @param images
     */
    private void schedulePostPublishActions(
            Content content,
            List<String> images
    ) {
        /**
         * 开启 AI 审核时：
         * 不再 afterCommit 直接发送 RabbitMQ，
         * 而是在 publish 的同一个事务里保存 Outbox。
         */
        //如果需要审核，发送 AI 审核任务
        if (shouldModerateContent()) {
            outboxEventService.createContentModerationEvent(
                    content,
                    images
            );
            return;
        }

        /**
         * 审核功能关闭时，暂时保留原来的自动通过规则。
         * 这个分支不是当前“帖子审核 MQ”链路，
         * 后面处理通知 Outbox 时再继续改造。
         */
        if (!isAutoApproveWhenModerationDisabled()) {
            return;
        }

        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            contentAuditService.approveContent(content.getContentId());
            return;
        }

        //事务提交后回调：自动通过内容
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        contentAuditService.approveContent(
                                content.getContentId()
                        );
                    }
                }
        );
    }

    // 根据配置判断是否需要审核内容
    private boolean shouldModerateContent() {
        if (!moderationProperties.isEnabled()) {
            return false;
        }
        AliyunModerationProperties.TargetConfig contentConfig = getContentTargetConfig();
        return contentConfig != null && contentConfig.isEnabled();
    }
    // 根据配置判断如果审核功能关闭了，是否自动通过内容

    private boolean isAutoApproveWhenModerationDisabled() {
        AliyunModerationProperties.TargetConfig contentConfig = getContentTargetConfig();
        String policy = contentConfig != null ? contentConfig.getDisabledPolicy() : "PENDING";
        return "APPROVED".equalsIgnoreCase(policy);
    }

    // 获取内容审核的目标配置
    private AliyunModerationProperties.TargetConfig getContentTargetConfig() {
        AliyunModerationProperties.Targets targets = moderationProperties.getTargets();
        return targets != null ? targets.getContent() : null;
    }

    /**
     * 推荐内容查询接口（推荐流个性化 D8：scene 收敛）。
     * scene 路由：latest / recommend / null → 画像流（RecommendRerankService 重排，
     * 游标入参忽略、曝光 set 为隐式游标）；hot → 既有 ZSET 热度序（曝光去重已取消）。
     *
     */
    @Transactional
    @Override
    public ScrollResult recommend(RecommendQueryDTO recommendQueryDTO) {

        // ========== 步骤 1：校验内容类型 ==========
        if (recommendQueryDTO.getContentType() != null && recommendQueryDTO.getContentType() != 1 && recommendQueryDTO.getContentType() != 2) {
            throw new ContentFailedException("内容类型必须为 1 或者 2或者 null");
        }

        // ========== 步骤 2：确定查询参数 ==========
        Long currentUserId = BaseContext.getCurrentId();
        int pageSize = (recommendQueryDTO.getPageSize() == null || recommendQueryDTO.getPageSize() <= 0) ? 5 : recommendQueryDTO.getPageSize();
        String scene = recommendQueryDTO.getScene();

        // ========== 步骤 3：scene 路由（D8） ==========
        // latest / recommend / null（对齐现状 null→latest 默认）统一进画像流
        if (scene == null || scene.equals("latest") || scene.equals("recommend")) {
            return recommendByProfileFlow(recommendQueryDTO, currentUserId, pageSize);
        }
        if (!"hot".equals(scene)) {
            // 非法 scene 异常语义不变
            throw new ContentFailedException("场景参数异常");
        }
        return recommendByHotFlow(recommendQueryDTO, pageSize);
    }

    /**
     * 画像流推荐（D8：scene=latest/recommend/null 统一路径）。
     * 召回、曝光过滤（隐式游标）、算分排序与曝光回写均在 RecommendRerankService 内完成；
     * 本方法只负责第 8~11 步 VO 装配与游标语义（minScore=null、offset=0，入参 lastScore/offset 忽略——
     * 契约变化依据总览 §4）。
     */
    private ScrollResult recommendByProfileFlow(RecommendQueryDTO recommendQueryDTO, Long currentUserId, int pageSize) {
        // 1. 重排：双池召回 → 曝光过滤 → 算分排序 → 截页 → 回写曝光（contentType 分类池透传）
        RecommendRerankService.RerankResult rerankResult = recommendRerankService.rerank(
                currentUserId, recommendQueryDTO.getContentType(), pageSize);

        List<Content> contents = rerankResult.contents();

        // 2. 空结果短路：池子耗尽或候选全被曝光/可见性过滤
        if (contents == null || contents.isEmpty()) {
            return ScrollResult.builder()
                    .list(new ArrayList<>())
                    .minScore(null)
                    .offset(0)
                    .hasMore(false)
                    .build();
        }

        // 3. 第 9~11 步装配：作者认证信息、点赞/收藏高亮、VO（保持 rerank 推荐序，不重排）
        List<ContentVO> voList = assembleContentVOs(contents);

        // 4. 画像流游标契约：minScore=null、offset=0（隐式游标，前端无需回传）
        return ScrollResult.builder()
                .minScore(null)
                .offset(0)
                .hasMore(rerankResult.hasMore())
                .list(voList)
                .build();
    }

    /**
     * 热度流推荐（D8：保持 ZSET 热度序与游标语义；曝光去重取消，无"滤光降级重拉"）。
     */
    private ScrollResult recommendByHotFlow(RecommendQueryDTO recommendQueryDTO, int pageSize) {
        // 热度流为了增加内容的多样性，允许多查询几条（pageSize * 3），后续在内存中截断到 pageSize 条
        int limit = pageSize * 3;
        double minScore = 0D;
        double maxScore = recommendQueryDTO.getLastScore() == null
                ? Double.MAX_VALUE
                : recommendQueryDTO.getLastScore();
        int offset = (recommendQueryDTO.getOffset() == null || recommendQueryDTO.getOffset() < 0) ? 0 : recommendQueryDTO.getOffset();

        // hot 池 key（contentType 分类池保持）
        String key = resolveRecommendHotKey(recommendQueryDTO.getContentType());

        // 步骤 4：循环拉取候选 id（无曝光过滤、无降级重拉）
        LoopFetchResult fetchResult = loopFetchRecommendIds(key, pageSize, maxScore, offset, limit, 5);

        // ========== 步骤 5：处理空结果 ==========
        if (fetchResult == null || fetchResult.ids.isEmpty()) {
            return ScrollResult.builder()
                    .list(new ArrayList<>())
                    .minScore(null)
                    .offset(0)
                    .hasMore(false)
                    .build();

        }
        // ========== 步骤 6：从 loopFetchRecommendIds 结果中提取数据 ==========
        List<Long> ids = fetchResult.ids;
        minScore = fetchResult.minScore;
        int os = fetchResult.offset;
        boolean hasMore = fetchResult.hasMore;

        // ========== 步骤 7：查询内容详情，并按 redis 里的顺序排列 ==========
        List<Content> contents = contentMapper.selectBatchIds(ids);
        if (contents != null && contents.size() > 1) {
            Map<Long, Integer> idIndexMap = new HashMap<>();
            for (int i = 0; i < ids.size(); i++) {
                idIndexMap.put(ids.get(i), i);
            }
            contents.sort(Comparator.comparingInt(c ->
                    idIndexMap.getOrDefault(c.getContentId(), Integer.MAX_VALUE)));
        }
        if (contents == null || contents.isEmpty()) {
            contents = new ArrayList<>();
        }

        // ========== 步骤 8~10：装配 VO（作者认证信息、点赞高亮） ==========
        List<ContentVO> voList = assembleContentVOs(contents);

        // ========== 步骤 11：返回结果（热度流游标语义不变） ==========
        return ScrollResult.builder()
                .minScore(minScore)//本次的最小时间戳，下次查询的 lastId
                .offset(os)//相同时间戳的偏移量
                .hasMore(hasMore)//是否有更多数据
                .list(voList)//内容列表
                .build();
    }

    /**
     * 推荐流 VO 装配（原 recommend() 第 8~11 步，画像流与热度流共用）：
     * 批量查询作者认证信息、点赞/收藏高亮、ContentVO 组装。
     * 输入 contents 的顺序即返回顺序（调用方保证推荐序/热度序），本方法不重排。
     */
    private List<ContentVO> assembleContentVOs(List<Content> contents) {
        if (contents == null || contents.isEmpty()) {
            return new ArrayList<>();
        }

        // 查询用户认证信息
        List<Long> userIds = contents.stream()
                .map(Content::getPublishUserId)
                .distinct()
                .collect(Collectors.toList());
        List<UserAuthInfoVO> userAuthList = userIds.isEmpty() ? new ArrayList<>() :
                userMapper.selectUserAuthInfoByIds(userIds);

        // 查询点赞和收藏高亮
        contents.forEach(this::isContentLiked);

        contents.forEach(this::isContentCollected);

        // 封装 VO
        Map<Long, UserAuthInfoVO> userAuthMap
                = userAuthList.stream()
                .collect(Collectors.toMap(UserAuthInfoVO::getUserId, u -> u, (v1, v2) -> v1));

        return contents.stream()
                .map(content -> {
                    UserAuthInfoVO userInfo = userAuthMap.getOrDefault(content.getPublishUserId(), new UserAuthInfoVO());
                    return convertContentToVO(content, userInfo);
                })
                .collect(Collectors.toList());
    }
    /**
     * 获取内容详情
     * 1. 参数校验--> 2. 查询内容详情--> 3. 获取发布用户id,并查询发布用户的信息
      * 4. 查询点赞高亮与收藏状态--> 5. 加入浏览历史--> 6.封装VO并返回
        */
    @Override
    public ContentVO getContentDetail(Long contentId) {
        //1.参数校验
        if (contentId == null) {
            throw new ContentFailedException("contentId不能为空");
        }
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
        if (cacheEntry.state() == ContentDetailState.INVALID_AUTHOR
                || cacheEntry.snapshot() == null) {
            throw new ContentFailedException("发布用户信息异常");
        }

        ContentDetailSnapshot snapshot = cacheEntry.snapshot();
        UserAuthInfoVO userInfo = authorProfileCache.get(snapshot.publishUserId());
        if (userInfo == null) {
            userInfo = new UserAuthInfoVO();
        }

        Content viewerState = Content.builder()
                .contentId(snapshot.contentId())
                .build();
        isContentLiked(viewerState);
        isContentCollected(viewerState);
        recordBrowseHistory(contentId);

        return convertDetailSnapshotToVO(snapshot, userInfo, viewerState);
    }

    private ContentVO convertDetailSnapshotToVO(
            ContentDetailSnapshot snapshot,
            UserAuthInfoVO userInfo,
            Content viewerState
    ) {
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





    /**
     * 删除内容
     * 1. 参数校验--> 2.查询内容是否存在
     * 3.判断是否是发布用户--> 4.物理删除内容图片--> 5.物理删除内容点赞记录
     * 6.物理删除评论下面的图片--> 7.物理删除评论下面的点赞记录--> 8.软删除评论
     * 9.如果是专业区，删除专业区内容--> 10.软删除内容本身
     * 11.事务提交后：删除redis中的内容（推荐流和热度流）和点赞记录；
     * 异步删除feed流中的内容；删除es中的内容；向量数据库删除内容
     * @param contentId
     */
    @Transactional
    @Override
    public void deleteContent(Long contentId) {
        //1.参数校验
        if (contentId == null) {
            throw new ContentFailedException("contentId不能为空");
        }
        //2.查询内容是否存在
        Content content = contentMapper.selectById(contentId);
        if (content == null) {
            throw new ContentFailedException("内容不存在");
        }

        //3.判断是否是发布用户
        Long publishUserId = content.getPublishUserId();
        Long userId = BaseContext.getCurrentId();
        if (!userId.equals(publishUserId)) {
            throw new ContentFailedException("您没有删除内容权限");
        }

        //4.物理删除内容图片
        contentMapper.deleteContentImages(contentId);
        //5.物理删除内容点赞记录
        contentMapper.deleteContentLikedByContentId(contentId);
        //补：删除收藏记录
        contentMapper.deleteContentCollectByContentId(contentId);
        //6.物理删除评论下面的图片
        contentMapper.deleteContentCommentImages(contentId);
        //7.物理删除评论下面的点赞记录
        contentMapper.deleteContentCommentLiked(contentId);
        //8.软删除评论
        contentMapper.softDeleteContentComment(contentId);
        // 删除前先记住已经进入 ES 的回答，软删除后由校准事件清理回答索引。
        List<QuestionAnswer> answers = content.getContentType() != null && content.getContentType() == 2
                ? questionMapper.selectAnswersByQuestionId(contentId)
                : List.of();

        //9.如果是专业区，删除专业区内容
        if (content.getContentType() != null && content.getContentType() == 2) {
            questionMapper.softDeleteAnswers(contentId);
        }
        //10.软删除内容本身

        contentMapper.softDeleteContent(contentId);

        // 删除状态和 Feed DELETE Outbox 在同一个事务中提交。
        outboxEventService.createFeedDeleteEvent(content);

        // 帖子和关联回答删除状态与 ES 校准事件一起提交。
        outboxEventService.createSearchReconcileEvent(ModerationTargetType.CONTENT.name(), contentId, "DELETE");
        for (QuestionAnswer answer : answers) {
            outboxEventService.createSearchReconcileEvent(ModerationTargetType.ANSWER.name(), answer.getAnswerId(), "PARENT_CONTENT_DELETE");
        }

        // 帖子删除事务成功后，提交时失效热榜聚合缓存。
        trendingCacheInvalidator.evictAfterCommit("content-delete");
        contentDetailCacheInvalidator.evictAfterCommit(contentId, "content-delete");

        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    //11.删除redis中的内容
                    //String key = RECOMMEND_CONTENT_KEY + "all:" + content.getContentType();
                    //stringRedisTemplate.opsForZSet().remove(key, contentId.toString());
                    //11.1 删最新推荐流
                    //混合池删除
                    stringRedisTemplate.opsForZSet().remove(RECOMMEND_ALL_KEY, contentId.toString());
                    //专业池或生活池删除
                    if (content.getContentType() != null) {
                        if (content.getContentType() == 1) {
                            stringRedisTemplate.opsForZSet().remove(RECOMMEND_LIFE_KEY, contentId.toString());
                        } else if (content.getContentType() == 2) {
                            stringRedisTemplate.opsForZSet().remove(RECOMMEND_PROFESSIONAL_KEY, contentId.toString());
                        }
                    }
                    //11.2 删热度推荐流
                    //混合池删除
                    stringRedisTemplate.opsForZSet().remove(RECOMMEND_HOT_ALL_KEY, contentId.toString());
                    //专业池或生活池删除
                    if (content.getContentType() != null) {
                        if (content.getContentType() == 1) {
                            stringRedisTemplate.opsForZSet().remove(RECOMMEND_HOT_LIFE_KEY, contentId.toString());
                        } else if (content.getContentType() == 2) {
                            stringRedisTemplate.opsForZSet().remove(RECOMMEND_HOT_PROFESSIONAL_KEY, contentId.toString());
                        }
                    }

                    //12.删除redis中的点赞记录

                    String likeKey = CONTENT_LIKED_KEY + contentId;
                    stringRedisTemplate.delete(likeKey);

                    //13.删除redis中的收藏记录
                    String collectKey = CONTENT_COLLECT_KEY + contentId;
                    stringRedisTemplate.delete(collectKey);

                    //16。向量数据库删除内容
                    contentVectorSyncService.deleteByContentId(contentId);
                }

            });
        }
    }






    /**
     * 查询我的浏览历史内容列表（C 端公开）
     * 核心步骤：
     * 1. 参数校验
     * 2. 分页查询（PageHelper）
     * 3. 查询浏览历史，获取 contentId 列表
     * 4. 判断 contentId 列表是否为空，若为空则直接返回空结果
     * 5.封装帖子VO并返回
     */
    @Override
    public PageVO<ContentVO> getMyBrowseHistoryContentList(Long userId, Integer current, Integer size) {
        //1.参数校验
        if (userId == null) {
            throw new ContentFailedException("userId不能为空");
        }
        if (current == null || current <= 0) {
            current = 1;
        }
        if (size == null || size <= 0) {
            size = 10;
        }

        //2.分页
        PageHelper.startPage(current, size);

        //3.查询浏览历史，获取contentId列表
        Page<Long> page = browseHistoryMapper.selectBrowseHistoryContentIds(userId);
        List<Long> contentIds = page.getResult();

        //4.判断contentId列表是否为空
        if (contentIds == null || contentIds.isEmpty()) {
            return PageVO.<ContentVO>builder()
                    .list(new ArrayList<>())
                    .pageSize(size)
                    .pageNum(current)
                    .totalPage(0)
                    .total(0L)
                    .hasMore(false)
                    .build();
        }

        //5.批量查询内容详情
        List<Content> contentList = contentMapper.selectBatchIds(contentIds);

        //6.过滤内容：只保留未删除且已通过审核的内容
        contentList = contentList.stream()
                .filter(content -> content.getIsDeleted() == 0)
                .filter(content -> content.getAuditStatus() != null && content.getAuditStatus() == AuditStatus.APPROVED.getCode())
                .toList();

        //7.查询所有作者的信息
        List<Long> userIds = contentList.stream()
                .map(Content::getPublishUserId)
                .distinct()
                .toList();
        List<UserAuthInfoVO> userAuthInfos = userIds.isEmpty() ? new ArrayList<>() :
                userMapper.selectUserAuthInfoByIds(userIds);

        //8.将用户信息转换为Map，key=userId，value=UserAuthInfoVO
        Map<Long, UserAuthInfoVO> userAuthMap = userAuthInfos.stream()
                .collect(Collectors.toMap(UserAuthInfoVO::getUserId,
                        u -> u,
                        (v1, v2) -> v1));

        //9.计算当前用户对每条内容的点赞状态与收藏状态
        contentList.forEach(this::isContentLiked);
        contentList.forEach(this::isContentCollected);

        //10.为每条数据找到对应的作者信息，并转换为VO
        List<ContentVO> contentVOList = contentList.stream()
                .map(content -> {
                    UserAuthInfoVO userInfo = userAuthMap.getOrDefault(content.getPublishUserId(),
                            new UserAuthInfoVO());
                    return convertContentToVO(content, userInfo);
                }).toList();


       //11.记录总数（过滤后实际条数）
        long total = contentList.size();
        Integer totalPages = (int) Math.ceil(total / (double) size);

        //12.判断是否有更多数据
        boolean hasMore = current < totalPages;

        //13.封装VO并返回
        return PageVO.<ContentVO>builder()
                .list(contentVOList)
                .pageSize(size)
                .pageNum(current)
                .totalPage(totalPages)
                .total(total)
                .hasMore(hasMore)
                .build();
    }



    /**
     * 清空浏览历史
     * 核心步骤：
     * 1. 参数校验
     * 2. 删除浏览历史记录
     */
    @Override
    public void clearBrowseHistory(Long userId) {
        //1.参数校验
        if (userId == null) {
            throw new ContentFailedException("userId不能为空");
        }
        //2.删除浏览历史记录
        browseHistoryMapper.clearBrowseHistory(userId);
    }



    /**
     * 分页查询用户已审核通过且未删除的帖子（C 端公开）
     * 1. 参数校验
     * 2. 分页查询（PageHelper）
     * 3. 查询用户公开帖子列表
     * 4.封装帖子（包含作者信息、点赞状态、收藏状态）VO并返回
     */
    @Override
    public PageVO<ContentVO> pageUserPublicContents(Long userId, Integer current, Integer size) {
        // 1. 参数校验
        if (userId == null) {
            throw new ContentFailedException("userId 不能为空");
        }
        if (current == null || current <= 0) {
            current = 1;
        }
        if (size == null || size <= 0) {
            size = 10;
        }

        // 2. 分页
        PageHelper.startPage(current, size);

        // 3. 查询用户公开帖子列表（已审核通过且未删除）
        Page<Content> page = contentMapper.pageUserPublicContents(userId);

        // 4. 批量查询作者信息，并转换为 Map
        List<Content> contentList = page.getResult();
        List<Long> userIds = contentList.stream()
                .map(Content::getPublishUserId)
                .distinct()
                .toList();
        List<UserAuthInfoVO> userAuthInfos = userIds.isEmpty() ? new ArrayList<>() :
                userMapper.selectUserAuthInfoByIds(userIds);
        Map<Long, UserAuthInfoVO> userAuthMap = userAuthInfos.stream()
                .collect(Collectors.toMap(UserAuthInfoVO::getUserId, u -> u, (v1, v2) -> v1));

        // 5. 计算当前用户对每条内容的点赞状态与收藏状态
        contentList.forEach(this::isContentLiked);
        contentList.forEach(this::isContentCollected);

        // 6. 为每条数据找到对应的作者信息，并转换为 VO
        List<ContentVO> contentVOList = contentList.stream()
                .map(content -> {
                    UserAuthInfoVO userInfo = userAuthMap.getOrDefault(content.getPublishUserId(), new UserAuthInfoVO());
                    return convertContentToVO(content, userInfo);
                })
                .toList();

        // 7. 记录总数，判断是否有更多数据，封装 VO 并返回
        long total = page.getTotal();
        Integer totalPages = (int) Math.ceil(total / (double) size);
        boolean hasMore = current < totalPages;

        return PageVO.<ContentVO>builder()
                .list(contentVOList)
                .pageSize(size)
                .pageNum(current)
                .totalPage(totalPages)
                .total(total)
                .hasMore(hasMore)
                .build();
    }






    /**
     * 查询我的帖子列表接口
     * 1.参数校验（userId不能为空，分页参数合理，审核状态参数合法）
     * 2.分页查询（PageHelper）
     * 3.查询我的帖子列表（根据userId和审核状态查询内容列表
     * 4.批量查询作者信息，并转换为Map，key=userId，value=UserAuthInfoVO，方便后续查找
     * 5.计算当前用户对每条内容的点赞状态与收藏状态
     * 6.为每条数据找到对应的作者信息，并转换为VO
     * 7，8，9.记录总数，判断是否有更多数据，封装VO并返回
     */


    @Override
    public PageVO<ContentVO> getMyContentList(Long userId, Integer current, Integer size, AuditStatus auditStatus) {
        //1.参数校验
        if (userId == null) {
            throw new ContentFailedException("userId不能为空");
        }
        if (current == null || current <= 0) {
            current = 1;
        }
        if (size == null || size <= 0) {
            size = 10;
        }
        //2.分页
        PageHelper.startPage(current, size);
        //3.查询我的帖子列表
        Page<Content> page = contentMapper.getMyContentsList(userId, auditStatus != null ? auditStatus.getCode() : null);
        //4.：批量查询作者信息，并转换为Map，key=userId，value=UserAuthInfoVO，方便后续查找
        List<Content> contentList = page.getResult();
        List<Long> userIds = contentList.stream()
                .map(Content::getPublishUserId)
                .distinct()
                .toList();
        List<UserAuthInfoVO> userAuthInfos = userIds.isEmpty() ? new ArrayList<>() :
                userMapper.selectUserAuthInfoByIds(userIds);
        Map<Long, UserAuthInfoVO> userAuthMap = userAuthInfos.stream()
                .collect(Collectors.toMap(UserAuthInfoVO::getUserId, u -> u, (v1, v2) -> v1));
        //5.计算当前用户对每条内容的点赞状态与收藏状态
        contentList.forEach(this::isContentLiked);
        contentList.forEach(this::isContentCollected);
      // 6.为每条数据找到对应的作者信息，并转换为VO
        List<ContentVO> contentVOList = contentList.stream()
                .map(content -> {
                    UserAuthInfoVO userInfo = userAuthMap.getOrDefault(content.getPublishUserId(), new UserAuthInfoVO());
                    return convertContentToVO(content, userInfo);
                })
                .toList();
        //7.记录总数
        long total = page.getTotal();//获取总记录数
        Integer totalPages = (int) Math.ceil(total / (double) size);//计算总页数
        //8.判断是否有更多数据
        boolean hasMore = current < totalPages;//当前页是否小于总页数
        //9.封装VO并返回
        PageVO<ContentVO> result = PageVO.<ContentVO>builder()
                .list(contentVOList)
                .pageSize(size)
                .pageNum(current)
                .totalPage(totalPages)
                .total(total)
                .hasMore(hasMore)
                .build();
        return result;
    }





    /**
     * 查询我点赞的帖子列表接口
     * 1.参数校验（userId不能为空，分页参数合理）
     * 2.分页查询（PageHelper）
     * 3.查询我点赞的帖子列表（根据userId查询内容列表
     * 4.批量查询作者信息，并转换为Map，key=userId，value=UserAuthInfoVO，方便后续查找
     * 5.计算当前用户对每条内容的点赞状态与收藏状态
     * 6.为每条数据找到对应的作者信息，并转换为VO
     * 7，8，9.记录总数，判断是否有更多数据，封装VO并返回
     */


    @Override
    public PageVO<ContentVO> getMyLikedContentList(Long userId, Integer current, Integer size) {

        //1.参数校验
        if (userId == null) {
            throw new ContentFailedException("userId不能为空");
        }
        if (current == null || current <= 0) {
            current = 1;
        }
        if (size == null || size <= 0) {
            size = 10;
        }
        //2.分页
        PageHelper.startPage(current, size);
        //3.查询我点赞的帖子列表
        Page<Content> page = contentMapper.getMyLikedContentList(userId);
        //4.查询所有作者的信息(先查ids，再差具体信息）
        List<Content> contentList = page.getResult();
        List<Long> userIds = contentList.stream()
                .map(Content::getPublishUserId)
                .distinct()
                .toList();

        List<UserAuthInfoVO> userAuthInfos = userIds == null || userIds.isEmpty() ? new ArrayList<>() :
                userMapper.selectUserAuthInfoByIds(userIds);

        //5.将用户信息转换为Map，key=userId，value=UserAuthInfoVO，方便后续查找
        Map<Long, UserAuthInfoVO> userAuthMap = userAuthInfos.stream()
                .collect(Collectors.toMap(UserAuthInfoVO::getUserId,
                        u -> u,
                        (v1, v2) -> v1));

        // 先计算当前用户对每条内容的点赞状态与收藏状态
        contentList.forEach(this::isContentLiked);
        contentList.forEach(this::isContentCollected);

        //6.为每条数据找到对应的作者信息，并转换为VO
        List<ContentVO> contentVOList = contentList.stream()
                .map(content -> {
                    UserAuthInfoVO userInfo = userAuthMap.getOrDefault(content.getPublishUserId(),
                            new UserAuthInfoVO());
                    return convertContentToVO(content, userInfo);
                }).toList();

        //7.记录总数
        long total = page.getTotal();//获取总记录数
        Integer totalPages = (int) Math.ceil(total / (double) size);//计算总页数
        //8.判断是否有更多数据
        boolean hasMore = current < totalPages;//当前页是否小于总页数
        //9.封装VO并返回
        PageVO<ContentVO> result = PageVO.<ContentVO>builder()
                .list(contentVOList)
                .pageSize(size)
                .pageNum(current)
                .totalPage(totalPages)
                .total(total)
                .hasMore(hasMore)
                .build();
        return result;


    }



    /**
     * 查询我收藏的帖子列表接口
     * 1.参数校验（userId不能为空，分页参数合理）
     * 2.分页查询（PageHelper）
     * 3.查询我收藏的帖子列表（根据userId查询内容列表
     * 4.批量查询作者信息，并转换为Map，key=userId，value=UserAuthInfoVO，方便后续查找
     * 5.计算当前用户对每条内容的点赞状态与收藏状态
     * 6.为每条数据找到对应的作者信息，并转换为VO
     * 7，8，9.记录总数，判断是否有更多数据，封装VO并返回
     */

    @Override
    public PageVO<ContentVO> getMyCollectContentList(Long userId, Integer current, Integer size) {
        //1.参数校验
        if (userId == null) {
            throw new ContentFailedException("userId不能为空");
        }
        if (current == null || current <= 0) {
            current = 1;
        }
        if (size == null || size <= 0) {
            size = 10;
        }
        //2.分页
        PageHelper.startPage(current, size);
        //3.查询我收藏的帖子列表
        Page<Content> page = contentMapper.getMyCollectContentList(userId);
        //4.查询所有作者的信息(先查ids，再差具体信息）
        List<Content> contentList = page.getResult();
        List<Long> userIds = contentList.stream().map(Content::getPublishUserId)
                .distinct()
                .toList();
        List<UserAuthInfoVO> userAuthInfos = userIds == null || userIds.isEmpty() ? new ArrayList<>() :
                userMapper.selectUserAuthInfoByIds(userIds);

        //5.将用户信息转换为Map，key=userId，value=UserAuthInfoVO，方便后续查找
        Map<Long, UserAuthInfoVO> userAuthMap = userAuthInfos.stream()
                .collect(Collectors.toMap(UserAuthInfoVO::getUserId,
                        u -> u,
                        (v1, v2) -> v1));
        //查询点赞与收藏高亮
        contentList.forEach(this::isContentLiked);
        contentList.forEach(this::isContentCollected);
        // 6.为每条数据找到对应的作者信息，并转换为VO
        List<ContentVO> contentVOList = contentList.stream()
                .map(content -> {
                    UserAuthInfoVO userInfo = userAuthMap.getOrDefault(content.getPublishUserId(),
                            new UserAuthInfoVO());
                    return convertContentToVO(content, userInfo);
                }).toList();


        //7.记录总数
        long total = page.getTotal();//获取总记录数
        Integer totalPages = (int) Math.ceil(total / (double) size);//计算总页数
        //8.判断是否有更多数据
        boolean hasMore = current < totalPages;//当前页是否小于总页数
        //9.封装VO并返回
        return PageVO.<ContentVO>builder()
                .list(contentVOList)
                .pageSize(size)
                .pageNum(current)
                .totalPage(totalPages)
                .total(total)
                .hasMore(hasMore)
                .build();
    }





    /**
     * 搜索内容（根据标题和内容模糊匹配）
     *
     * @param searchDTO
     * @return
     */
    @Override
    public PageVO<ContentVO> searchContent(SearchDTO searchDTO) {
        //1.参数校验
        if (searchDTO == null || StringUtils.isBlank(searchDTO.getKeyword())) {
            throw new SearchFailedException("搜索关键词不能为空");
        }
        String keyword = searchDTO.getKeyword().trim();//去除前后空格

        if (keyword.length() > 50) {
            throw new SearchFailedException("搜索关键词不能超过50字");
        }
        if (searchDTO.getContentType() != null && searchDTO.getContentType() != 1 && searchDTO.getContentType() != 2) {
            throw new SearchFailedException("内容类型必须为1或2");
        }

        Integer current = (searchDTO.getCurrent() == null || searchDTO.getCurrent() <= 0) ? 1 : searchDTO.getCurrent();
        Integer pageSize = (searchDTO.getPageSize() == null || searchDTO.getPageSize() <= 0) ? 10 : searchDTO.getPageSize();
        //2.分页
        // PageHelper.startPage(current, pageSize);
        //3.执行搜索（模糊匹配标题和内容）
        // Page<Content> page = contentMapper.searchContent(keyword, searchDTO.getContentType());
        Page<Content> page = elasticSearchService.searchContent(
                keyword,
                searchDTO.getContentType(),
                current,
                pageSize
        );

        List<Content> contentList = page.getResult();
        if (contentList == null || contentList.isEmpty()) {
            recordHistory(BaseContext.getCurrentId(), keyword);
            return PageVO.<ContentVO>builder()
                    .list(List.of())
                    .pageNum(current)
                    .pageSize(pageSize)
                    .total(page.getTotal())
                    .totalPage(0)
                    .hasMore(false)
                    .build();
        }

        //4.查询用户信息
        List<Long> userIds = contentList.stream()
                .map(Content::getPublishUserId)
                .distinct()
                .toList();
        List<UserAuthInfoVO> userAuthInfos = userMapper.selectUserAuthInfoByIds(userIds);
        Map<Long, UserAuthInfoVO> userAuthMap = userAuthInfos.stream()
                .collect(Collectors.toMap(UserAuthInfoVO::getUserId,
                        u -> u,
                        (v1, v2) -> v1));
        //5.转换为VO列表
        List<ContentVO> contentVOList = contentList.stream()
                .map(content -> {
                    UserAuthInfoVO userInfo = userAuthMap.getOrDefault(content.getPublishUserId(),
                            new UserAuthInfoVO());
                    return convertContentToVO(content, userInfo);
                }).toList();
        //6.记录总数
        long total = page.getTotal();//获取总记录数
        Integer totalPages = (int) Math.ceil(total / (double) pageSize);//计算总页数
        //7.判断是否有更多数据
        boolean hasMore = current < totalPages;//当前页是否小于总页数


        //8.插入搜索记录
        Long userId = BaseContext.getCurrentId();
        recordHistory(userId, keyword);

        //8.封装VO并返回
        return PageVO.<ContentVO>builder()
                .list(contentVOList)
                .pageNum(current)
                .pageSize(pageSize)
                .total(total)
                .totalPage(totalPages)
                .hasMore(hasMore)
                .build();

    }









    //--------------------------以下是私有方法----------------------------------
    //--------------------------以下是私有方法----------------------------------
    //--------------------------以下是私有方法----------------------------------


    //解析最新推荐流的Redis Key
    private String resolveRecommendKey(Integer contentType) {
        if (contentType == null) {
            return RECOMMEND_ALL_KEY;
        }
        if (contentType == 1) {
            return RECOMMEND_LIFE_KEY;
        }
        if (contentType == 2) {
            return RECOMMEND_PROFESSIONAL_KEY;
        }
        throw new ContentFailedException("内容类型必须为 1 或 2");
    }

    //解析热度推荐流的Redis Key
    public String resolveRecommendHotKey(Integer contentType) {
        if (contentType == null) {
            return RECOMMEND_HOT_ALL_KEY;
        }
        if (contentType == 1) {
            return RECOMMEND_HOT_LIFE_KEY;
        }
        if (contentType == 2) {
            return RECOMMEND_HOT_PROFESSIONAL_KEY;
        }
        throw new ContentFailedException("内容类型必须为 1 或 2");
    }


    /**
     * 将新发布的内容添加到 最新推荐流（Redis ZSET）中，分数为发布时间戳
     */
    public void publishToRedis(Long contentId, LocalDateTime createTime, Integer contentType) {
        //String key = RECOMMEND_CONTENT_KEY + "all:" + contentType;
        // 分数为时间戳（毫秒）
        double score = createTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        //1)混合池
        stringRedisTemplate.opsForZSet().add(RECOMMEND_ALL_KEY, contentId.toString(), score);
        //2)专业池或生活池
        String specificKey = resolveRecommendKey(contentType);
        stringRedisTemplate.opsForZSet().add(specificKey, contentId.toString(), score);

    }

    /**
     * 将发布内容添加到热度流（Redis ZSET）中，分数为热度分
     *
     */
    private void publishToHotRedis(Content content) {
        //计算热度分（公式唯一真源 HotScoreCalculator，禁止复制公式）
        double hotScore = HotScoreCalculator.calculate(content);
        //混合池
        stringRedisTemplate.opsForZSet().add(RECOMMEND_HOT_ALL_KEY, content.getContentId().toString(), hotScore);
        //专业池或生活池
        String specificKey = resolveRecommendHotKey(content.getContentType());
        stringRedisTemplate.opsForZSet().add(specificKey, content.getContentId().toString(), hotScore);
    }

    /**
     * 记录浏览历史
     *
     * @param contentId 内容ID
     */
    private void recordBrowseHistory(Long contentId) {
        try {
            Long userId = BaseContext.getCurrentId();
            if (userId == null) {
                return;
            }
            BrowseHistory browseHistory = BrowseHistory.builder()
                    .userId(userId)
                    .contentId(contentId)
                    .browseDate(LocalDate.now())
                    .build();
            browseHistoryMapper.insertOrUpdateBrowseHistory(browseHistory);
        } catch (Exception e) {
            log.error("记录浏览历史失败: contentId={}", contentId, e);
        }
    }

    /**
     * 记录搜索历史
     *
     * @param userId
     * @param keyword
     */
    private void recordHistory(Long userId, String keyword) {
        // 防御性清洗：去除首尾空格，空关键词直接返回
        keyword = StringUtils.trim(keyword);
        if (StringUtils.isBlank(keyword)) {
            return;
        }
        try {
            //1.查询是否已有记录
            SearchHistory existHistory = searchMapper.selectByUserIdAndKeyword(userId, keyword);

            //2.没有记录，插入新记录,有记录，更新搜索时间
            if (existHistory != null) {
                //更新搜索时间
                searchMapper.updateSearchTime(userId, keyword, LocalDateTime.now());
            } else {
                //插入新记录
                SearchHistory searchHistory = SearchHistory.builder()
                        .userId(userId)
                        .keyword(keyword)
                        .isDeleted(0)
                        .createTime(LocalDateTime.now())
                        .build();
                searchMapper.insertSearchHistory(searchHistory);
            }

        } catch (Exception e) {
            log.error("记录搜索历史失败:userId={},keyword={}", userId, keyword, e);
            //不抛出异常，记录日志即可
        }
    }

    /**
     * 将 Content和UserAuthInfoVO 实体转换为 ContentVO 视图对象
     * 包含内容信息、用户信息、图片列表
     *
     * @param content  内容实体
     * @param userInfo 用户认证信息（可能为空对象）
     * @return ContentVO 视图对象
     */
    private ContentVO convertContentToVO(Content content, UserAuthInfoVO userInfo) {
        List<ContentImage> contentImages = contentMapper.selectImagesByContentIds(content.getContentId());
        // 处理图片列表：如果为 null 则返回空列表，否则提取图片 URL 并过滤空字符串
        List<String> imageUrls = contentImages == null ? new ArrayList<>() :
                contentImages.stream()
                        .map(ContentImage::getImageUrl)
                        .filter(StringUtils::isNotBlank)
                        .collect(Collectors.toList());

        return ContentVO.builder()
                .contentId(content.getContentId())
                .contentType(content.getContentType())
                .title(content.getTitle())
                .content(content.getContent())
                .liked(content.getLiked() == null ? 0 : content.getLiked())
                .commentCount(content.getCommentCount() == null ? 0 : content.getCommentCount())
                .collectCount(content.getCollectCount() == null ? 0 : content.getCollectCount())
                .publishUserId(content.getPublishUserId())
                .avatarUrl(userInfo.getAvatarUrl())           // 用户头像
                .nickName(userInfo.getNickName())          // 用户昵称
                .quantaDepartment(userInfo.getQuantaDepartment()) // 用户部门
                .quantaBatch(userInfo.getQuantaBatch())          // 用户届数
                .auditStatus(content.getAuditStatus())
                .createTime(content.getCreateTime())
                .images(imageUrls)
                .isLiked(BooleanUtil.isTrue(content.getIsLiked()))
                .isCollected(BooleanUtil.isTrue(content.getIsCollected()))
                .build();
    }

    /**
     * 根据 MySQL 当前状态校准 Redis 热度。
     * 重复执行只会覆盖相同分数，不会重复累加。
     */
    @Override
    public void reconcileHotScore(Long contentId) {
        if (contentId == null) {
            throw new ContentFailedException("热度校准缺少帖子 ID");
        }

        Content content = contentMapper.selectById(contentId);

        // 先清理全部热度池，避免删除、驳回或内容类型变化后残留旧数据。
        removeHotScoreFromRedis(contentId);

        if (content == null || !AuditStatus.APPROVED.getCode().equals(content.getAuditStatus()) || !Integer.valueOf(0).equals(content.getIsDeleted())) {
            log.info("帖子不可见，已从热度池移除，contentId={}", contentId);
            return;
        }

        // 消息只负责提醒，最终分数始终根据 MySQL 当前计数重新计算（公式唯一真源 HotScoreCalculator）
        double hotScore = HotScoreCalculator.calculate(content);
        stringRedisTemplate.opsForZSet().add(RECOMMEND_HOT_ALL_KEY, contentId.toString(), hotScore);
        stringRedisTemplate.opsForZSet().add(resolveRecommendHotKey(content.getContentType()), contentId.toString(), hotScore);
        log.info("帖子热度校准完成，contentId={}, hotScore={}", contentId, hotScore);
    }

    private void removeHotScoreFromRedis(Long contentId) {
        String member = contentId.toString();
        stringRedisTemplate.opsForZSet().remove(RECOMMEND_HOT_ALL_KEY, member);
        stringRedisTemplate.opsForZSet().remove(RECOMMEND_HOT_LIFE_KEY, member);
        stringRedisTemplate.opsForZSet().remove(RECOMMEND_HOT_PROFESSIONAL_KEY, member);
    }

    /**
     * 计算内容热度分（公式唯一真源已提取至 HotScoreCalculator，本方法保留为接口兼容委托）。
     * 公式：热度分 = (点赞数×3 + 评论数×2 + 收藏数×5) / (时间衰减系数)
     * 时间衰减系数 = (当前时间 - 发布时间的小时数 + 2) ^ 1.5
     *
     * @param content 内容实体
     * @return 热度分
     */
    @Override
    public double calculateHotScore(Content content) {
        return HotScoreCalculator.calculate(content);
    }

    /**
     * 封装查询点赞高亮
     */
    private void isContentLiked(Content content) {
        Long userId = BaseContext.getCurrentId();
        if (userId == null) {
            content.setIsLiked(false);
            return;
        }
        String key = CONTENT_LIKED_KEY + content.getContentId();
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        if (score != null) {
            content.setIsLiked(true);
            return;
        }
        boolean likedInDb = contentMapper.countContentLiked(content.getContentId(), userId) > 0;
        content.setIsLiked(likedInDb);
        if (likedInDb) {
            stringRedisTemplate.opsForZSet().add(key, userId.toString(), System.currentTimeMillis());
        }
    }

    /**
     * 封装查询收藏状态
     */
    private void isContentCollected(Content content) {
        Long userId = BaseContext.getCurrentId();
        if (userId == null) {
            content.setIsCollected(false);
            return;
        }
        String key = CONTENT_COLLECT_KEY + content.getContentId();
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        if (score != null) {
            content.setIsCollected(true);
            return;
        }
        boolean collectedInDb = contentMapper.countContentCollect(content.getContentId(), userId) > 0;
        content.setIsCollected(collectedInDb);
        if (collectedInDb) {
            stringRedisTemplate.opsForZSet().add(key, userId.toString(), System.currentTimeMillis());
        }
    }


//    /**
//     * Tuple 元数据封装
//     * 作用：存储 contentId 和对应的 score，用于 LinkedHashMap 去重保序
//     */
//    private static class TupleMeta {
//        Long contentId;
//        Double score;
//
//        public TupleMeta(Long contentId, Double score) {
//            this.contentId = contentId;
//            this.score = score;
//        }
//    }


    //过滤和排序方法
    private FilterResult filterAndSort(Set<ZSetOperations.TypedTuple<String>> idsWithScores) {
        List<Long> ids = new ArrayList<>();
        List<ZSetOperations.TypedTuple<String>> filteredTuples = new ArrayList<>();

        //1.过滤（过滤掉脏数据；曝光去重已随 D8 取消，热度流不再读曝光 set）
        for (ZSetOperations.TypedTuple<String> tuple : idsWithScores) {
            if (tuple == null || tuple.getValue() == null || tuple.getScore() == null) {
                continue;
            }

            String contentIdStr = tuple.getValue();


            Long contentId;
            try {
                contentId = Long.valueOf(contentIdStr);
            } catch (NumberFormatException e) {
                log.warn("Redis ZSET 脏数据，跳过: {}", contentIdStr);
                continue;
            }

            ids.add(contentId);//先收集id，后续查询内容信息时一起过滤掉不存在的id，避免多次访问数据库
            filteredTuples.add(tuple);//
        }
        //2.二级排序（根据分数从大到小）
        filteredTuples.sort((a, b) -> {
            int byScore = Double.compare(b.getScore(), a.getScore());
            if (byScore != 0) return byScore;
            return Long.compare(Long.parseLong(b.getValue()), Long.parseLong(a.getValue()));
        });
        //3.排序后的tuples生成新的ids列表
        ids = filteredTuples.stream()
                .map(tuple -> Long.valueOf(tuple.getValue()))
                .collect(Collectors.toList());


        return new FilterResult(ids, filteredTuples);
    }


    /**
     * 循环拉取方法
     *
     * @param key           Redis ZSET key
     * @param pageSize      每页大小
     * @param initMaxScore  初始最大分数（第一次拉取时为正无穷，后续根据上次结果更新）
     * @param initOffset    初始偏移量（第一次拉取时为1，后续根据上次结果更新）
     * @param perFetchLimit 每次从Redis拉取的数量（建议设置为 pageSize 的2-3倍，避免过滤后数据不足）
     * @param maxLoop       最大循环次数（避免死循环，建议设置为3-5次）
     * @return 循环拉取结果封装类，包含最终的内容ID列表、下一页游标（minScore和offset）以及是否有更多数据
     */
    private LoopFetchResult loopFetchRecommendIds(
            String key,
            int pageSize,
            double initMaxScore,
            int initOffset,
            int perFetchLimit,
            int maxLoop) {

        //1.LinkedList候选池，保持有序且方便头部插入和尾部删除，有序且去重（LinkedHashMap实现）
        LinkedHashMap<Long, Double> idScoreMap = new LinkedHashMap<>();
        //2.游标初始化
        double cursorScore = initMaxScore;
        int cursorOffset = initOffset;

        //3.状态标记

        int targetCount = pageSize + 1;//还需要的内容数量
        boolean sourceExhausted = false;//数据源是否已经拉取到底了（例如Redis中没有更多数据了）

        //4.循环拉取并计算游标
        for (int loop = 0; loop < maxLoop; loop++) {
            //4.1从Redis拉取数据（根据当前游标）， 从大到小拉取，符合推荐场景的分数递减趋势
            Set<ZSetOperations.TypedTuple<String>> tuples = stringRedisTemplate.opsForZSet()
                    .reverseRangeByScoreWithScores(key, 0, cursorScore, cursorOffset, perFetchLimit);
            //关键：如果本次拉取没有数据，说明已经到底了，退出循环
            if (tuples == null || tuples.isEmpty()) {
                sourceExhausted = true;
                break;
            }
            //4.2过滤+排序，过滤掉脏数据，排序保持分数从大到小（如果分数相同，根据contentId从大到小）
            FilterResult filterResult = filterAndSort(tuples);
            List<ZSetOperations.TypedTuple<String>> filteredTuples = filterResult.tuples;
            //4.3将过滤排序后的结果加入候选池（保持有序且去重）
            for (ZSetOperations.TypedTuple<String> tuple : filteredTuples) {
                if (tuple == null || tuple.getValue() == null || tuple.getScore() == null) {
                    continue;//过滤掉脏数据
                }
                Long contentId = Long.valueOf(tuple.getValue());
                Double score = tuple.getScore();
                idScoreMap.putIfAbsent(contentId, score);//保持有序且去重（），目的是为了后续根据分数计算下一轮游标
            }//如果不去重，可能会导致同一内容多次出现在候选池中，影响游标计算和最终结果的准确性
            //有重复的原因是：Redis ZSET中可能存在分数相同的多个内容，且每次拉取的数量可能超过这些同分内容的数量，导致同一批次中出现重复内容；
            // 或者不同批次中出现同一内容（例如用户在短时间内发布了多条内容，分数相同，跨批次分布）。
            //4.4计算下一次拉取的游标
            CursorResult cursorResult = nextCursorFromBatch(tuples, cursorScore, cursorOffset);
            cursorScore = cursorResult.minScore;
            cursorOffset = cursorResult.offset;
            //4.5如果候选池中的内容数量已经满足要求，提前退出循环
            if (idScoreMap.size() >= targetCount) {
                break;
            }
            // 4.6 本轮返回不足 perFetchLimit，说明到底了，退出循环
            if (tuples.size() < perFetchLimit) {
                sourceExhausted = true;
                break;
            }

        }

        //5.提取最终ids
        List<Long> allIds = new ArrayList<>(idScoreMap.keySet());

        // 6. 截断到真正返回给前端的一页
        List<Long> finalIds = allIds.size() > pageSize
                ? new ArrayList<>(allIds.subList(0, pageSize))
                : allIds;

        //7.判断hasMore并截断
        // 判断 hasMore
        boolean hasMore;
        if (finalIds.isEmpty()) {
            // 没有数据，明确
            hasMore = false;
        } else if (allIds.size() > pageSize) {
            // 明确拿到了 pageSize+1
            hasMore = true;
        } else if (sourceExhausted) {
            // 明确到底
            hasMore = false;
        } else {
            // 未确认到底（例如 maxLoop 到上限），保守给 true
            hasMore = true;
        }

        // 8. 计算返回游标（基于实际返回的最后一条）
        double returnMinScore = cursorScore;
        int returnOffset = cursorOffset;
        if (!finalIds.isEmpty()) {
            Long lastId = finalIds.get(finalIds.size() - 1);
            Double lastScore = idScoreMap.get(lastId);
            if (lastScore != null) {
                returnMinScore = lastScore;
                returnOffset = 0;


                // 统计同分数量（在最终返回的数据中）
                for (Long id : finalIds) {
                    if (Double.compare(idScoreMap.get(id), returnMinScore) == 0) {
                        returnOffset++;
                    }
                }
                // 关键：如果返回游标分数等于初始游标分数，说明是同分跨页，需要叠加 initOffset
                if (Double.compare(returnMinScore, initMaxScore) == 0) {
                    returnOffset += initOffset;
                }
            }
        }
        return new LoopFetchResult(finalIds, returnMinScore, returnOffset, hasMore);

    }

    /**
     * 计算下一轮游标
     *
     * @param batch           Redis 返回的一批数据（未过滤前的原始数据，包含 contentId 和 score）
     * @param currentMaxScore 当前游标分数
     * @param currentOffset   当前游标偏移量
     * @return 下一轮游标（minScore, offset）
     * 找到最后一条有效数据的分数，作为下一轮的 minScore；
     * 计算本批中分数等于 minScore 的数量，作为下一轮的 offset；
     *
     */
    private CursorResult nextCursorFromBatch(
            Set<ZSetOperations.TypedTuple<String>> batch,
            double currentMaxScore,
            int currentOffset) {

        // 如果 batch 为空，返回当前游标
        if (batch == null || batch.isEmpty()) {
            return new CursorResult(currentMaxScore, currentOffset);
        }

        // 找最后一个有效的 tuple
        ZSetOperations.TypedTuple<String> lastValid = null;
        for (ZSetOperations.TypedTuple<String> tuple : batch) {
            if (tuple != null && tuple.getValue() != null && tuple.getScore() != null) {
                lastValid = tuple;
            }
        }

        // 如果没找到有效 tuple，返回当前游标
        if (lastValid == null) {
            return new CursorResult(currentMaxScore, currentOffset);
        }

        // 新游标分数 = 最后一条的 score
        double newMaxScore = lastValid.getScore();

        // 新游标偏移量 = 本批中 score 等于 newMaxScore 的数量
        int newOffset = 0;
        for (ZSetOperations.TypedTuple<String> tuple : batch) {
            if (tuple != null && tuple.getScore() != null && Double.compare(tuple.getScore(), newMaxScore) == 0) {
                newOffset++;
            }
        }
        // 关键：如果新游标分数等于当前游标分数，说明是同一分数跨批次，需要叠加 currentOffset
        if (Double.compare(newMaxScore, currentMaxScore) == 0) {
            newOffset += currentOffset;
        }


        return new CursorResult(newMaxScore, newOffset);
    }
    //封装过滤排序结果
//先定义两个内部类，用于封装抽取方法的返回值。
// 这能解决**"不要依赖外部可变变量"**的问题。

    /**
     * 过滤排序结果封装
     */
    private static class FilterResult {
        List<Long> ids;
        List<ZSetOperations.TypedTuple<String>> tuples;

        public FilterResult(List<Long> ids, List<ZSetOperations.TypedTuple<String>> tuples) {
            this.ids = ids;
            this.tuples = tuples;

        }
    }

    /**
     * 游标计算结果封装
     */
    private static class CursorResult {
        double minScore;
        int offset;

        public CursorResult(double minScore, int offset) {
            this.minScore = minScore;
            this.offset = offset;
        }
    }

    /**
     * 循环拉取结果封装
     */
    private static class LoopFetchResult {
        List<Long> ids;
        double minScore;
        int offset;
        boolean hasMore;

        public LoopFetchResult(List<Long> ids, double minScore, int offset, boolean hasMore) {
            this.ids = ids;
            this.minScore = minScore;
            this.offset = offset;
            this.hasMore = hasMore;
        }
    }
}
