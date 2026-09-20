package com.quanta.demo0.service.Impl;

import com.quanta.demo0.entity.Content;
import com.quanta.demo0.exception.ContentFailedException;
import com.quanta.demo0.mapper.ContentMapper;
import com.quanta.demo0.rag.vector.ContentVectorSyncService;
import com.quanta.demo0.service.ContentExposureService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static com.quanta.demo0.constant.RedisConstants.*;

/**
 * 内容曝光服务实现类
 * 核心职责：
 * 1. 审核通过的内容 → 写入推荐池（Redis ZSet）+ 推送到粉丝 Feed 流 + 同步到 ES/向量库
 * 2. 审核驳回的内容 → 从推荐池移除 + 从粉丝 Feed 流删除 + 从 ES/向量库删除
 * 3. Redis 预热：系统启动时从数据库批量加载已审核通过的内容到推荐池
 * 调用时机：
 * - exposeApprovedContent：AI 审核通过、管理端人工审核通过时调用
 * - hideRejectedContent：AI 审核驳回、管理端人工驳回、用户删除内容时调用
 * - warmUpRecommendRedisFromDb：系统启动时或推荐池数据丢失时调用
 * 设计要点：
 * - 多存储同步：Redis（推荐池）+ MQ（Feed 流推送）+ ES（搜索）+ 向量库（RAG 检索）
 * - 异常隔离：try-catch 包裹，单个存储失败不影响其他存储
 * - 内容类型路由：根据 contentType 分发到不同的推荐池（生活/专业）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContentExposureServiceImpl implements ContentExposureService {

    /** Redis 操作模板，用于推荐池 ZSet 读写 */
    private final StringRedisTemplate stringRedisTemplate;
    /** 向量同步服务，同步内容到向量数据库（RAG 检索用） */
    private final ContentVectorSyncService contentVectorSyncService;
    /** 内容 Mapper，查询数据库内容 */
    private final ContentMapper contentMapper;

    /**
     * 审核通过的内容曝光（写入推荐池 + 推送 Feed 流 + 同步 ES/向量库）
     * 执行流程：
     * 1. 写入 Redis 推荐池（按时间排序的 ZSet）
     *    - RECOMMEND_ALL_KEY：全量推荐池
     *    - RECOMMEND_LIFE_KEY / RECOMMEND_PROFESSIONAL_KEY：分类推荐池
     * 2. 写入 Redis 热度推荐池（按热度分排序的 ZSet）
     *    - RECOMMEND_HOT_ALL_KEY：全量热度池
     *    - RECOMMEND_HOT_LIFE_KEY / RECOMMEND_HOT_PROFESSIONAL_KEY：分类热度池
     * 3. 发送 Feed 流推送消息（MQ 异步推送到粉丝首页）
     * 4. 同步到 Elasticsearch（普通搜索用）
     * 5. 同步到向量数据库（RAG 检索用）
     * 异常处理：try-catch 包裹，单个存储失败不影响其他存储，记录错误日志
     * 
     * @param content 审核通过的内容实体
     */
    @Override
    public void exposeApprovedContent(Content content) {
        // 参数校验：content 或 contentId 为空则直接返回
        if (content == null || content.getContentId() == null) {
            return;
        }
        try {
            // 1. 写入 Redis 推荐池（按创建时间排序）
            publishToRedis(content.getContentId(), content.getCreateTime(), content.getContentType());
            // 2. 写入 Redis 热度推荐池（按热度分排序）
            publishToHotRedis(content);
            // 5. 同步到向量数据库（RAG 检索用）
            contentVectorSyncService.upsertByContentId(content.getContentId());

            log.info("内容曝光完成 contentId={}", content.getContentId());
        } catch (Exception e) {
            log.error("内容曝光失败 contentId={}", content.getContentId(), e);
        }
    }

    /**
     * 审核驳回/删除的内容隐藏（从推荐池移除 + 清理 Feed 流 + 删除 ES/向量库）
     * 执行流程：
     * 1. 从 Redis 推荐池移除（6 个 ZSet：全量/生活/专业 × 时间/热度）
     * 2. 发送 Feed 流删除消息（MQ 异步从粉丝首页移除）
     * 3. 从 Elasticsearch 删除（普通搜索不再返回）
     * 4. 从向量数据库删除（RAG 检索不再返回）
     * 调用时机：
     * - AI 审核驳回
     * - 管理端人工驳回
     * - 用户删除内容
     * - 内容被举报后判定违规
     * 
     * @param contentId 需要隐藏的内容 ID
     */
    @Override
    public void hideRejectedContent(Long contentId) {
        // 参数校验：contentId 为空则直接返回
        if (contentId == null) {
            return;
        }
        // 查询内容实体（需要 publishUserId 和 contentType 用于 Feed 流删除）
        Content content = contentMapper.selectById(contentId);
        if (content == null) {
            return;
        }
        try {
            // 1. 从 Redis 推荐池移除（6 个 ZSet）
            // 时间排序推荐池
            stringRedisTemplate.opsForZSet().remove(RECOMMEND_ALL_KEY, contentId.toString());
            stringRedisTemplate.opsForZSet().remove(RECOMMEND_LIFE_KEY, contentId.toString());
            stringRedisTemplate.opsForZSet().remove(RECOMMEND_PROFESSIONAL_KEY, contentId.toString());
            // 热度排序推荐池
            stringRedisTemplate.opsForZSet().remove(RECOMMEND_HOT_ALL_KEY, contentId.toString());
            stringRedisTemplate.opsForZSet().remove(RECOMMEND_HOT_LIFE_KEY, contentId.toString());
            stringRedisTemplate.opsForZSet().remove(RECOMMEND_HOT_PROFESSIONAL_KEY, contentId.toString());

            // 4. 从向量数据库删除（RAG 检索不再返回）
            contentVectorSyncService.deleteByContentId(contentId);

            log.info("内容曝光清理完成 contentId={}", contentId);
        } catch (Exception e) {
            log.error("内容曝光清理失败 contentId={}", contentId, e);
        }
    }

    /**
     * 写入 Redis 推荐池（按创建时间排序的 ZSet）
     * ZSet 结构：key = contentId, score = 创建时间戳（毫秒）
     * 写入位置：
     * - RECOMMEND_ALL_KEY：全量推荐池（所有类型内容）
     * - RECOMMEND_LIFE_KEY / RECOMMEND_PROFESSIONAL_KEY：分类推荐池（按 contentType 分发）
     * @param contentId 内容 ID
     * @param createTime 创建时间（用于计算 ZSet score）
     * @param contentType 内容类型（1=生活，2=专业）
     */
    private void publishToRedis(Long contentId, LocalDateTime createTime, Integer contentType) {
        // ZSet score = 创建时间戳（毫秒），保证按时间倒序排列
        double score = createTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        // 写入全量推荐池
        stringRedisTemplate.opsForZSet().add(RECOMMEND_ALL_KEY, contentId.toString(), score);
        // 写入分类推荐池（根据 contentType 路由到生活/专业池）
        stringRedisTemplate.opsForZSet().add(resolveRecommendKey(contentType), contentId.toString(), score);
    }

    /**
     * 写入 Redis 热度推荐池（按热度分排序的 ZSet）
     * ZSet 结构：key = contentId, score = 热度分（Hacker News 算法）
     * 写入位置：
     * - RECOMMEND_HOT_ALL_KEY：全量热度池
     * - RECOMMEND_HOT_LIFE_KEY / RECOMMEND_HOT_PROFESSIONAL_KEY：分类热度池
     * @param content 内容实体（用于计算热度分）
     */
    private void publishToHotRedis(Content content) {
        // 计算热度分（点赞×3 + 评论×2 + 收藏×5，再除以时间衰减因子）
        double hotScore = calculateHotScore(content);
        // 写入全量热度池
        stringRedisTemplate.opsForZSet().add(RECOMMEND_HOT_ALL_KEY, content.getContentId().toString(), hotScore);
        // 写入分类热度池
        stringRedisTemplate.opsForZSet().add(
                resolveRecommendHotKey(content.getContentType()),
                content.getContentId().toString(),
                hotScore);
    }

    /**
     * 根据内容类型解析推荐池 Key（时间排序）
     * @param contentType 内容类型（null=全量，1=生活，2=专业）
     * @return Redis Key
     */
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

    /**
     * 根据内容类型解析热度推荐池 Key（热度排序）
     * @param contentType 内容类型（null=全量，1=生活，2=专业）
     * @return Redis Key
     */
    private String resolveRecommendHotKey(Integer contentType) {
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
     * 计内容热度分（Hacker News 算法变种）
     * 公式：hotScore = (点赞×3 + 评论×2 + 收藏×5) / (小时数 + 2)^1.5
     * 设计要点：
     * - 权重分配：收藏(5) > 点赞(3) > 评论(2)，收藏代表最高认可度
     * - 时间衰减：(hours + 2)^1.5，新内容衰减慢，旧内容衰减快
     * - 保底分数：无互动内容给 20 分基础分，避免完全沉底
     * @param content 内容实体
     * @return 热度分
     */
    private double calculateHotScore(Content content) {
        // 获取互动数据（null 安全处理）
        int liked = content.getLiked() == null ? 0 : content.getLiked();
        int commentCount = content.getCommentCount() == null ? 0 : content.getCommentCount();
        int collectCount = content.getCollectCount() == null ? 0 : content.getCollectCount();
        
        // 计算基础分（加权求和）
        double baseScore = liked * 3 + commentCount * 2 + collectCount * 5;

        // 计算时间衰减因子
        LocalDateTime createTime = content.getCreateTime() != null ? content.getCreateTime() : LocalDateTime.now();
        long hours = Duration.between(createTime, LocalDateTime.now()).toHours();
        double timeDecay = Math.pow(hours + 2, 1.5);
        
        // 计算热度分
        double hotScore = baseScore / timeDecay;
        
        // 保底分数：无互动内容给 20 分基础分（避免完全沉底）
        if (baseScore == 0) {
            hotScore = 20.0 / timeDecay;
        }
        return hotScore;
    }

    /**
     * Redis 推荐池预热（从数据库批量加载已审核通过的内容）
     * 调用时机：
     * - 系统首次启动时，推荐池为空
     * - Redis 数据丢失或重启后
     * - 手动触发数据修复
     * 执行流程：
     * 1. 分页查询数据库中已审核通过的内容（auditStatus=1, isDeleted=0）
     * 2. 逐条写入 Redis 推荐池（时间排序 + 热度排序）
     * 3. 循环直到所有数据加载完成
     * 设计要点：
     * - 分批加载：避免一次性加载大量数据导致 OOM
     * - 空值跳过：content 或 contentId 为空时跳过，避免脏数据
     * - 终止条件：查询结果少于 batchSize 说明已到最后一页
     * @param batchSize 每批加载数量（默认 100）
     * @return 成功加载的内容总数
     */
    @Override
    public int warmUpRecommendRedisFromDb(int batchSize) {
        // 参数校验：batchSize 非法则使用默认值 100
        if (batchSize <= 0) {
            batchSize = 100;
        }
        int offset = 0;  // 分页偏移量
        int total = 0;   // 成功加载的总数
        while (true) {
            // 分页查询已审核通过的内容
            List<Content> batch = contentMapper.selectApprovedForRecommendWarmup(offset, batchSize);
            // 无数据则终止
            if (batch == null || batch.isEmpty()) {
                break;
            }
            // 逐条写入 Redis 推荐池
            for (Content content : batch) {
                // 跳过脏数据
                if (content == null || content.getContentId() == null) {
                    continue;
                }
                LocalDateTime createTime = content.getCreateTime() != null
                        ? content.getCreateTime()
                        : LocalDateTime.now();
                publishToRedis(content.getContentId(), createTime, content.getContentType());
                publishToHotRedis(content);
                total++;
            }
            // 查询结果少于 batchSize 说明已到最后一页，终止循环
            if (batch.size() < batchSize) {
                break;
            }
            offset += batchSize;  // 下一页
        }
        return total;
    }
}
