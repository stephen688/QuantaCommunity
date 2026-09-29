package com.quanta.demo0.content.service.impl;

import com.quanta.demo0.answer.entity.QuestionAnswer;
import com.quanta.demo0.content.dto.ContentDTO;
import com.quanta.demo0.content.entity.Content;
import com.quanta.demo0.content.entity.ContentImage;
import com.quanta.demo0.content.exception.ContentFailedException;
import com.quanta.demo0.content.service.ContentAuditService;
import com.quanta.demo0.content.service.ContentCommandService;
import com.quanta.demo0.content.service.ContentDetailCacheInvalidator;
import com.quanta.demo0.content.vo.ContentVO;
import com.quanta.demo0.mapper.ContentMapper;
import com.quanta.demo0.mapper.QuestionMapper;
import com.quanta.demo0.interaction.service.ContentInteractionService;
import com.quanta.demo0.moderation.enums.ModerationTargetType;
import com.quanta.demo0.moderation.properties.AliyunModerationProperties;
import com.quanta.demo0.moderation.utils.SensitiveWordChecker;
import com.quanta.demo0.platform.common.enums.AuditStatus;
import com.quanta.demo0.platform.mq.service.OutboxEventService;
import com.quanta.demo0.platform.redis.constant.RedisConstants;
import com.quanta.demo0.platform.security.context.BaseContext;
import com.quanta.demo0.rag.vector.ContentVectorSyncService;
import com.quanta.demo0.search.service.impl.TrendingCacheInvalidator;
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
 */
@Service
@Slf4j
public class ContentCommandServiceImpl implements ContentCommandService {

    @Autowired
    private ContentMapper contentMapper;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private QuestionMapper questionMapper;
    @Autowired
    private SensitiveWordChecker sensitiveWordChecker;
    @Autowired
    private OutboxEventService outboxEventService;
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
     * 发布后处理：根据审核配置创建 Outbox 或在提交后自动通过。
     */
    private void schedulePostPublishActions(Content content, List<String> images) {
        if (shouldModerateContent()) {
            outboxEventService.createContentModerationEvent(content, images);
            return;
        }

        if (!isAutoApproveWhenModerationDisabled()) {
            return;
        }

        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            contentAuditService.approveContent(content.getContentId());
            return;
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                contentAuditService.approveContent(content.getContentId());
            }
        });
    }

    private boolean shouldModerateContent() {
        if (!moderationProperties.isEnabled()) {
            return false;
        }
        AliyunModerationProperties.TargetConfig contentConfig = getContentTargetConfig();
        return contentConfig != null && contentConfig.isEnabled();
    }

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
        contentMapper.deleteContentCommentImages(contentId);
        contentMapper.deleteContentCommentLiked(contentId);
        contentMapper.softDeleteContentComment(contentId);

        List<QuestionAnswer> answers = content.getContentType() != null && content.getContentType() == 2
                ? questionMapper.selectAnswersByQuestionId(contentId)
                : List.of();

        if (content.getContentType() != null && content.getContentType() == 2) {
            questionMapper.softDeleteAnswers(contentId);
        }
        contentMapper.softDeleteContent(contentId);

        outboxEventService.createFeedDeleteEvent(content);
        outboxEventService.createSearchReconcileEvent(
                ModerationTargetType.CONTENT.name(), contentId, "DELETE");
        for (QuestionAnswer answer : answers) {
            outboxEventService.createSearchReconcileEvent(
                    ModerationTargetType.ANSWER.name(), answer.getAnswerId(), "PARENT_CONTENT_DELETE");
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
