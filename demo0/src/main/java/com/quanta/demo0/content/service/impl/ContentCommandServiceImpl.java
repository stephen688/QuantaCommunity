package com.quanta.demo0.content.service.impl;

import com.quanta.demo0.answer.service.AnswerCommandService;
import com.quanta.demo0.comment.service.CommentCommandService;
import com.quanta.demo0.content.dto.ContentDTO;
import com.quanta.demo0.content.entity.Content;
import com.quanta.demo0.content.entity.ContentImage;
import com.quanta.demo0.content.exception.ContentFailedException;
import com.quanta.demo0.content.service.ContentAuditService;
import com.quanta.demo0.content.service.ContentCommandService;
import com.quanta.demo0.content.service.ContentDetailCacheInvalidator;
import com.quanta.demo0.content.vo.ContentVO;
import com.quanta.demo0.content.mapper.ContentMapper;
import com.quanta.demo0.interaction.service.ContentInteractionService;
import com.quanta.demo0.content.mq.producer.ContentEventProducer;
import com.quanta.demo0.moderation.enums.ModerationTargetType;
import com.quanta.demo0.moderation.properties.AliyunModerationProperties;
import com.quanta.demo0.moderation.utils.SensitiveWordChecker;
import com.quanta.demo0.platform.common.enums.AuditStatus;
import com.quanta.demo0.search.mq.producer.SearchEventProducer;
import com.quanta.demo0.platform.redis.constant.RedisConstants;
import com.quanta.demo0.platform.security.context.BaseContext;
import com.quanta.demo0.rag.vector.ContentVectorSyncService;
import com.quanta.demo0.search.service.TrendingCacheInvalidator;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static com.quanta.demo0.platform.redis.constant.RedisConstants.CONTENT_COLLECT_KEY;
import static com.quanta.demo0.platform.redis.constant.RedisConstants.CONTENT_LIKED_KEY;
import static com.quanta.demo0.platform.redis.constant.RedisConstants.RECOMMEND_ALL_KEY;
import static com.quanta.demo0.platform.redis.constant.RedisConstants.RECOMMEND_HOT_ALL_KEY;
import static com.quanta.demo0.platform.redis.constant.RedisConstants.RECOMMEND_HOT_LIFE_KEY;
import static com.quanta.demo0.platform.redis.constant.RedisConstants.RECOMMEND_HOT_PROFESSIONAL_KEY;
import static com.quanta.demo0.platform.redis.constant.RedisConstants.RECOMMEND_LIFE_KEY;
import static com.quanta.demo0.platform.redis.constant.RedisConstants.RECOMMEND_PROFESSIONAL_KEY;

/**
 * 内容命令侧实现。
 *
 * <p>该类是从原内容服务中抽取出的渐进迁移实现，保持原有事务边界、
 * Outbox 写入、缓存失效、审核和向量清理语义不变。</p>
 *
 * 本类只保留两个命令入口：发布（publish）与删除（deleteContent）。
 *
 * ============================================================
 * 【为什么这个类叫 CommandService？—— CQRS 的轻量版】
 * ============================================================
 * 重构前这里塞在 1983 行的 ContentServiceImpl 里（同时管发布、审核、推荐、点赞、搜索……）。
 * 拆分思路：把"写操作"（Command：发布/删除）和"读操作"（Query：列表/详情）分开成两个类。
 *
 * 注意：这是**工程拆分**，不是教科书 CQRS —— 没有独立读写库、没有事件溯源。
 * 好处很朴素：写路径要处理事务/幂等/缓存失效，读路径要处理缓存命中/N+1，
 * 两拨人关心的东西完全不同，拆开后每个类 300 行以内，改写不影响读。
 *
 * 【面试追问：写操作为什么要在事务里同步调 Counter Service，而不发事件？】
 * 点赞数/评论数这种计数，如果在事务里发 MQ 事件等消费者去 +1，
 * 会出现"帖子刚发布、详情页计数还是 0"的可见性窗口。
 * 所以计数走同事务同步调用（见 deleteContent 里 contentInteractionService.deleteByContentId），
 * **事件只负责对"最终一致"容忍的下游**（推荐流、搜索索引、向量库）。
 * —— "同步 vs 异步"的判断标准：这个数据是否必须在本次事务提交后立即可见。
 *
 * ============================================================
 * 【本类最重要的一条横线：MySQL 是事实源，Redis 只是投影】
 * ============================================================
 * 看删除逻辑的顺序就明白：
 *   1. 先写 MySQL（软删）
 *   2. 再写 Outbox（和 MySQL 同一个事务 —— 这是 Outbox 模式的全部意义）
 *   3. 事务提交后（afterCommit）才动 Redis（删 ZSET 成员、删互动标记、删向量）
 *
 * 为什么 Redis 动作要 afterCommit？—— 因为事务还没提交时删了 Redis，
 * 一旦事务回滚，MySQL 里帖子还活着，但推荐流里已经没了 —— 缓存和事实源反向不一致，
 * 而且没有任何机制会把它修回来。
 * 顺序反过来（提交后删缓存）即使 afterCommit 那一步机器挂了，
 * 下游还有 TTL 兜底、对账兜底 —— **失败模式必须选"可自愈"的那个方向**。
 */
@Service
@Slf4j
public class ContentCommandServiceImpl implements ContentCommandService {

    @Autowired
    private ContentMapper contentMapper;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private AnswerCommandService answerCommandService;
    @Autowired
    private CommentCommandService commentCommandService;
    @Autowired
    private SensitiveWordChecker sensitiveWordChecker;
    @Autowired
    private ContentEventProducer contentEventProducer;
    @Autowired
    private SearchEventProducer searchEventProducer;
    @Autowired
    private AliyunModerationProperties moderationProperties;
    @Autowired
    private ContentAuditService contentAuditService;
    @Autowired
    private TrendingCacheInvalidator trendingCacheInvalidator;
    @Autowired
    private ContentVectorSyncService contentVectorSyncService;
    @Autowired
    private ContentDetailCacheInvalidator contentDetailCacheInvalidator;
    @Autowired
    private ContentInteractionService contentInteractionService;

    /**
     * 发布内容（帖子/回答）。
     *
     * ============================================================
     * 【审核策略：三态配置，而不是硬编码】
     * ============================================================
     * 发布后内容去哪，由 moderation.properties 的三个配置位决定：
     *   1. 机审开启           → 写 Outbox 审核事件，等 MQ 消费者调阿里云审（异步链路）
     *   2. 机审关闭 + policy=APPROVED → 事务提交后自动 approve（直接可见）
     *   3. 机审关闭 + policy=PENDING  → 什么都不做，停在 PENDING 等人工审
     *
     * 为什么这值得讲：**行为开关放配置，代码只实现状态机**。
     * 演示环境关掉机审秒过，生产环境打开机审走全链路 —— 同一份代码，不改一行。
     *
     * 【敏感词为什么在参数校验之前？】
     * 顺序无所谓对错，但敏感词命中返回的信息（"标题包含敏感词：xxx"）
     * 不能泄露词库 —— 只返回第一个命中的词，不返回"共命中 N 个"。
     *
     * 【面试追问：为什么发布里没有发"搜索索引"事件，删除里却有？】
     * 因为新帖子还在 PENDING，搜索不到未审核内容是产品语义。
     * 审核通过（approveContent）那一刻才会发 SEARCH_INDEX 事件 ——
     * **事件跟着状态迁移走，不跟着 CRUD 走**。
     */
    @Override
    @Transactional
    public ContentVO publish(ContentDTO contentDTO) {
        String firstHit = sensitiveWordChecker.findFirstHit(contentDTO.getTitle());
        if (firstHit != null) {
            throw new ContentFailedException("标题包含敏感词：" + firstHit);
        }

        firstHit = sensitiveWordChecker.findFirstHit(contentDTO.getContent());
        if (firstHit != null) {
            throw new ContentFailedException("内容包含敏感词：" + firstHit);
        }

        Long publishUserId = BaseContext.getCurrentId();
        if (contentDTO.getContentType() == null
                || (contentDTO.getContentType() != 1 && contentDTO.getContentType() != 2)) {
            throw new ContentFailedException("内容类型必须为 1 或者 2");
        }
        if (StringUtils.isBlank(contentDTO.getTitle())) {
            throw new ContentFailedException("标题不能为空");
        }
        if (contentDTO.getTitle().length() > 50) {
            throw new ContentFailedException("标题不能超过 50 字");
        }
        if (StringUtils.isBlank(contentDTO.getContent())) {
            throw new ContentFailedException("内容不能为空");
        }
        if (contentDTO.getContent().length() > 500) {
            throw new ContentFailedException("内容不能超过 500 字");
        }

        // 图片列表校验
        List<String> images = contentDTO.getImages() == null
                ? new ArrayList<>()
                : contentDTO.getImages().stream()
                .filter(StringUtils::isNotBlank)
                .toList();
        if (images.size() > 5) {
            throw new ContentFailedException("图片列表最多 5 张");
        }

        Content content = Content.builder()
                .contentType(contentDTO.getContentType())
                .title(contentDTO.getTitle())
                .content(contentDTO.getContent())
                .publishUserId(publishUserId)
                .auditStatus(AuditStatus.PENDING.getCode())
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

        if (content.getContentId() == null) {
            throw new ContentFailedException("内容发布失败，请稍后重试");
        }

        // 插入图片关联
        if (!images.isEmpty()) {
            List<ContentImage> contentImages = new ArrayList<>();
            for (int i = 0; i < images.size(); i++) {
                contentImages.add(ContentImage.builder()
                        .contentId(content.getContentId())
                        .imageUrl(images.get(i))
                        .sort(i)
                        .createTime(LocalDateTime.now())
                        .build());
            }
            contentMapper.batchInsertImages(contentImages);
        }

        // 发布后处理
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

        // 刷新缓存
        contentDetailCacheInvalidator.evictAfterCommit(content.getContentId(), "content-publish");
        return contentVO;
    }

    /**
     * 发布后处理：根据审核配置创建 Outbox 或在提交后自动通过。
     *
     * ============================================================
     * 【为什么要注册 afterCommit 回调，而不是事务里直接调 approveContent？】
     * ============================================================
     * 自动通过（APPROVED 策略）的路径里，approveContent 自己也会开事务、
     * 还要发 SEARCH_INDEX 事件、刷新推荐流 —— 这些都依赖"发布事务已经提交"这个前提。
     *
     * 如果在发布事务还没提交时就直接调它，会出两个问题：
     *   1. **事件先于数据**：审核通过发的 SEARCH_INDEX 事件被消费者抢到，
     *      去 MySQL 查帖子 —— 发布事务还没提交，查不到，消费失败进重试；
     *   2. REQUIRED 传播下 approveContent 会**加入外层事务**，
     *      "审核通过"的持久化被绑在发布的命运上 —— 发布回滚连审核记录一起没了
     *      （这个方向倒是无害的，但语义上审核是独立的状态迁移，不该寄生）。
     *
     * 所以：同一事务里只做"必须原子"的事（insert + Outbox），
     * 一切"提交之后才允许发生"的动作（approve、刷缓存、删 ZSET）全部 afterCommit。
     * **判断标准：这个动作的前提是数据已可见吗？是 → afterCommit。**
     *
     * 注意 isActualTransactionActive 的分支：没有事务时直接同步调用，
     * registerSynchronization 在无事务时会抛异常 —— 这是测试环境/内部调用的兜底。
     */
    private void schedulePostPublishActions(Content content, List<String> images) {
        if (shouldModerateContent()) {
            contentEventProducer.createContentModerationEvent(content, images);
            return;
        }

        if (!isAutoApproveWhenModerationDisabled()) {
            return;
        }

        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            // 无事务时直接同步调用
            contentAuditService.approveContent(content.getContentId());
            return;
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                // 事务提交后自动通过
                contentAuditService.approveContent(content.getContentId());
            }
        });
    }

    private boolean shouldModerateContent() {
        if (!moderationProperties.isEnabled()) {
            return false;
        }
        // 文本审核 是否开启
        AliyunModerationProperties.TargetConfig contentConfig = getContentTargetConfig();
        return contentConfig != null && contentConfig.isEnabled();
    }

    // 是否自动通过
    private boolean isAutoApproveWhenModerationDisabled() {
        AliyunModerationProperties.TargetConfig contentConfig = getContentTargetConfig();
        String policy = contentConfig != null ? contentConfig.getDisabledPolicy() : "PENDING";
        return "APPROVED".equalsIgnoreCase(policy);
    }

    private AliyunModerationProperties.TargetConfig getContentTargetConfig() {
        AliyunModerationProperties.Targets targets = moderationProperties.getTargets();
        return targets != null ? targets.getContent() : null;
    }

    /**
     * 删除内容，并在事务提交后清理推荐流、互动状态和向量索引。
     *
     * ============================================================
     * 【删除是最能看清"数据一致性分层"的方法 —— 从内到外四层】
     * ============================================================
     * 第 1 层 —— 事实源（同事务，强一致）：
     *   contentMapper.softDeleteContent + 删图片 + 删互动明细 + 删评论/回答。
     *   注意是**软删**（is_deleted 标记），为什么：评论、点赞明细都外键式引用着 contentId，
     *   硬删要么级联删一大串（删一个帖子牵出几百行），要么留孤儿行。
     *   软删让"删帖"变成一次状态迁移，所有下游按状态过滤即可。
     *
     * 第 2 层 —— 通知下游（Outbox，同事务写入）：
     *   Feed 删除事件、搜索对账事件 —— 和软删在**同一个 DB 事务**里提交。
     *   这就是 Outbox 模式：不直接发 MQ（发了 MQ 但事务回滚 = 幽灵消息），
     *   而是先把"要发的消息"当业务数据写进 outbox 表，提交后由 Dispatcher 投递。
     *   【面试高频】为什么不 @Transactional 里直接 rabbitTemplate.convertAndSend？
     *   —— 消息不持久、事务回滚消息已飞、消费者先于数据可见，三个坑 Outbox 全解。
     *
     * 第 3 层 —— 缓存失效（afterCommit）：
     *   trendingCacheInvalidator + contentDetailCacheInvalidator，
     *   先删缓存再等 TTL，而不是刷新值 —— **失效 vs 更新选失效**（并发写更新会互相覆盖旧值）。
     *
     * 第 4 层 —— 投影清理（afterCommit）：
     *   6 个推荐 ZSET 移成员、2 个互动 Set 删 key、向量库删条目。
     *   全部可以丢给 TTL/对账兜底 —— 这里丢了不要紧，是"最终一致"层。
     *
     * 【面试追问：为什么 ZSET 移除要按 contentType 分池子删三处？】
     * 因为推荐池是"all + 分类池"双写结构（见 RECOMMEND_* 六个 key），
     * ZADD 时写了两个池子，删除就必须对称地删两个 —— **写扩散的地方，清理也要对称扩散**。
     * 漏删一个池子的后果：帖子从列表消失了，但从另一个 tab 还能刷到 —— 典型的"删不干净"bug。
     *
     * 【面试追问：先删 MySQL 再删 Redis，中间用户读到了什么？】
     *   - 走列表 → ZSET 里还有这个 contentId（第 4 层还没执行）→ 点进详情 → 详情缓存被第 3 层失效
     *     → loader 查 MySQL → 软删了 → 返回 NOT_FOUND → 写进负缓存 60s。
     *   - 也就是说**中间窗口用户会看到"列表里有、点进去 404"** —— 这是可接受的最终一致窗口，
     *   缓解手段就是详情负缓存（防反复点）+ Feed 侧异步删除越快窗口越小。
     */
    @Override
    @Transactional
    public void deleteContent(Long contentId) {
        if (contentId == null) {
            throw new ContentFailedException("contentId不能为空");
        }

        Content content = contentMapper.selectById(contentId);
        if (content == null) {
            throw new ContentFailedException("内容不存在");
        }

        Long publishUserId = content.getPublishUserId();
        Long userId = BaseContext.getCurrentId();
        if (!userId.equals(publishUserId)) {
            throw new ContentFailedException("您没有删除内容权限");
        }

        contentMapper.deleteContentImages(contentId);
        contentInteractionService.deleteByContentId(contentId);
        commentCommandService.deleteByContentId(contentId);

        List<Long> answerIds = content.getContentType() != null && content.getContentType() == 2
                ? answerCommandService.deleteByQuestionId(contentId)
                : List.of();

        contentMapper.softDeleteContent(contentId);

        contentEventProducer.createFeedDeleteEvent(content);
        searchEventProducer.createSearchReconcileEvent(
                ModerationTargetType.CONTENT.name(), contentId, "DELETE");
        for (Long answerId : answerIds) {
            searchEventProducer.createSearchReconcileEvent(
                    ModerationTargetType.ANSWER.name(), answerId, "PARENT_CONTENT_DELETE");
        }

        trendingCacheInvalidator.evictAfterCommit("content-delete");
        contentDetailCacheInvalidator.evictAfterCommit(contentId, "content-delete");

        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    stringRedisTemplate.opsForZSet().remove(RECOMMEND_ALL_KEY, contentId.toString());
                    if (content.getContentType() != null) {
                        if (content.getContentType() == 1) {
                            stringRedisTemplate.opsForZSet().remove(RECOMMEND_LIFE_KEY, contentId.toString());
                        } else if (content.getContentType() == 2) {
                            stringRedisTemplate.opsForZSet().remove(RECOMMEND_PROFESSIONAL_KEY, contentId.toString());
                        }
                    }

                    stringRedisTemplate.opsForZSet().remove(RECOMMEND_HOT_ALL_KEY, contentId.toString());
                    if (content.getContentType() != null) {
                        if (content.getContentType() == 1) {
                            stringRedisTemplate.opsForZSet().remove(RECOMMEND_HOT_LIFE_KEY, contentId.toString());
                        } else if (content.getContentType() == 2) {
                            stringRedisTemplate.opsForZSet().remove(RECOMMEND_HOT_PROFESSIONAL_KEY, contentId.toString());
                        }
                    }

                    stringRedisTemplate.delete(CONTENT_LIKED_KEY + contentId);
                    stringRedisTemplate.delete(CONTENT_COLLECT_KEY + contentId);
                    contentVectorSyncService.deleteByContentId(contentId);
                }
            });
        }
    }
}
