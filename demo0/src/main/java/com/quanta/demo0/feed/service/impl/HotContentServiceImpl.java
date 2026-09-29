package com.quanta.demo0.feed.service.impl;

import com.quanta.demo0.content.entity.Content;
import com.quanta.demo0.content.exception.ContentFailedException;
import com.quanta.demo0.content.service.ContentQueryService;
import com.quanta.demo0.content.vo.ContentSnapshotVO;
import com.quanta.demo0.feed.service.HotContentService;
import com.quanta.demo0.feed.utils.HotScoreCalculator;
import com.quanta.demo0.platform.common.enums.AuditStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;

import static com.quanta.demo0.platform.redis.constant.RedisConstants.RECOMMEND_ALL_KEY;
import static com.quanta.demo0.platform.redis.constant.RedisConstants.RECOMMEND_HOT_ALL_KEY;
import static com.quanta.demo0.platform.redis.constant.RedisConstants.RECOMMEND_HOT_LIFE_KEY;
import static com.quanta.demo0.platform.redis.constant.RedisConstants.RECOMMEND_HOT_PROFESSIONAL_KEY;
import static com.quanta.demo0.platform.redis.constant.RedisConstants.RECOMMEND_LIFE_KEY;
import static com.quanta.demo0.platform.redis.constant.RedisConstants.RECOMMEND_PROFESSIONAL_KEY;

/** 热榜/最新推荐池写入和热度校准。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HotContentServiceImpl implements HotContentService {

    private final StringRedisTemplate stringRedisTemplate;
    private final ContentQueryService contentQueryService;

    @Override
    public String resolveRecommendKey(Integer contentType) {
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

    @Override
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

    @Override
    public void publishToRedis(Long contentId, LocalDateTime createTime, Integer contentType) {
        double score = createTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        stringRedisTemplate.opsForZSet().add(RECOMMEND_ALL_KEY, contentId.toString(), score);
        stringRedisTemplate.opsForZSet().add(resolveRecommendKey(contentType), contentId.toString(), score);
    }

    private void publishToHotRedis(Content content) {
        double hotScore = calculateHotScore(content);
        stringRedisTemplate.opsForZSet().add(RECOMMEND_HOT_ALL_KEY, content.getContentId().toString(), hotScore);
        stringRedisTemplate.opsForZSet().add(resolveRecommendHotKey(content.getContentType()),
                content.getContentId().toString(), hotScore);
    }

    @Override
    public void reconcileHotScore(Long contentId) {
        if (contentId == null) {
            throw new ContentFailedException("热度校准缺少帖子 ID");
        }

        ContentSnapshotVO snapshot = contentQueryService.getContentSnapshots(java.util.List.of(contentId))
                .stream().findFirst().orElse(null);
        removeHotScoreFromRedis(contentId);
        if (snapshot == null
                || !AuditStatus.APPROVED.getCode().equals(snapshot.getAuditStatus())
                || !Integer.valueOf(0).equals(snapshot.getIsDeleted())) {
            log.info("帖子不可见，已从热度池移除，contentId={}", contentId);
            return;
        }

        Content content = Content.builder()
                .contentId(snapshot.getContentId())
                .contentType(snapshot.getContentType())
                .createTime(snapshot.getCreateTime())
                .liked(snapshot.getLikedCount())
                .commentCount(snapshot.getCommentCount())
                .collectCount(snapshot.getCollectCount())
                .build();
        publishToHotRedis(content);
        log.info("帖子热度校准完成，contentId={}, hotScore={}", contentId, calculateHotScore(content));
    }

    private double calculateHotScore(Content content) {
        return HotScoreCalculator.calculate(content);
    }

    private void removeHotScoreFromRedis(Long contentId) {
        String member = contentId.toString();
        stringRedisTemplate.opsForZSet().remove(RECOMMEND_HOT_ALL_KEY, member);
        stringRedisTemplate.opsForZSet().remove(RECOMMEND_HOT_LIFE_KEY, member);
        stringRedisTemplate.opsForZSet().remove(RECOMMEND_HOT_PROFESSIONAL_KEY, member);
    }
}
