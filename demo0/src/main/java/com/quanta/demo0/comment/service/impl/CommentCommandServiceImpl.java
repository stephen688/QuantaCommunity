package com.quanta.demo0.comment.service.impl;

import com.quanta.demo0.comment.dto.CommentAddDTO;
import com.quanta.demo0.comment.entity.CommentImage;
import com.quanta.demo0.comment.entity.ContentComment;
import com.quanta.demo0.comment.exception.CommentFailedException;
import com.quanta.demo0.comment.policy.CommentZonePolicy;
import com.quanta.demo0.comment.service.CommentAuditService;
import com.quanta.demo0.comment.service.CommentCommandService;
import com.quanta.demo0.comment.service.CommentCounterService;
import com.quanta.demo0.answer.service.AnswerQueryService;
import com.quanta.demo0.answer.vo.AnswerSnapshotVO;
import com.quanta.demo0.content.service.ContentDetailCacheInvalidator;
import com.quanta.demo0.content.service.ContentQueryService;
import com.quanta.demo0.content.vo.ContentSnapshotVO;
import com.quanta.demo0.comment.mapper.CommentMapper;
import com.quanta.demo0.interaction.service.CommentInteractionService;
import com.quanta.demo0.moderation.enums.ModerationTargetType;
import com.quanta.demo0.moderation.properties.AliyunModerationProperties;
import com.quanta.demo0.moderation.utils.SensitiveWordChecker;
import com.quanta.demo0.platform.common.enums.AuditStatus;
import com.quanta.demo0.comment.mq.producer.CommentEventProducer;
import com.quanta.demo0.feed.mq.producer.FeedEventProducer;
import com.quanta.demo0.search.mq.producer.SearchEventProducer;
import com.quanta.demo0.platform.redis.constant.RedisConstants;
import com.quanta.demo0.platform.security.context.BaseContext;
import com.quanta.demo0.platform.security.properties.QuantabotProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 评论命令服务实现。
 *
 * 负责评论发布与删除，保留审核、Outbox、Bot mention 和详情缓存失效语义。
 * 查询与点赞/举报分别由 CommentQueryServiceImpl 和 CommentInteractionServiceImpl 承担。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class CommentCommandServiceImpl implements CommentCommandService {

    private final CommentMapper commentMapper;
    private final ContentQueryService contentQueryService;
    private final AnswerQueryService answerQueryService;
    private final CommentInteractionService commentInteractionService;
    private final SensitiveWordChecker sensitiveWordChecker;
    private final CommentZonePolicy commentZonePolicy;
    private final AliyunModerationProperties moderationProperties;
    private final QuantabotProperties quantabotProperties;
    private final CommentAuditService commentAuditService;
    private final FeedEventProducer feedEventProducer;
    private final SearchEventProducer searchEventProducer;
    private final CommentEventProducer commentEventProducer;
    private final ContentDetailCacheInvalidator contentDetailCacheInvalidator;
    private final StringRedisTemplate stringRedisTemplate;
    private final CommentCounterService commentCounterService;

    /**
     * 校验并写入评论、图片和用户行为事件；审核开启时同时写入审核 Outbox。
     *
     * @param commentAddDTO 评论发布请求
     * @return 新评论 ID
     */
    @Transactional
    @Override
    public Long sendComment(CommentAddDTO commentAddDTO) {
        //获取用户id
        Long userId = BaseContext.getCurrentId();


        //1.校验内容（是否不为空且未被删除，是否通过审核，是否超过500，是否有敏感词）
        Long contentId = commentAddDTO.getContentId();
        ContentSnapshotVO content = contentQueryService.getContentSnapshot(contentId);
        if (content == null || content.getAuditStatus() != 1) {
            throw new CommentFailedException("内容不存在或未通过审核");
        }
        int length = commentAddDTO.getContent().length();
        int maxLength = commentZonePolicy.getMaxLength(content.getContentType());
        if (length > maxLength) {
            throw new CommentFailedException("内容长度超过"+maxLength+"字");
        }
        //校验敏感词
        String firstHit = sensitiveWordChecker.findFirstHit(commentAddDTO.getContent());
        if (firstHit != null) {
            throw new CommentFailedException("评论内容包含敏感词: " + firstHit);
        }


        //2如果父级评论不是0，检查父级评论是否存在（是否被删除），
        // 校验父级评论的内容id是否与前端的一致，校验父级评论的parentID是否为0

        if (commentAddDTO.getParentId() != null) {
            ContentComment parentComment = commentMapper.selectById(commentAddDTO.getParentId());

            if (parentComment == null) {
                throw new CommentFailedException("父级评论不存在或已被删除");
            }
            if (!parentComment.getContentId().equals(commentAddDTO.getContentId())) {
                throw new CommentFailedException("父级评论的内容id与评论的一致不一致");
            }
            if (parentComment.getParentId() != null) {
                throw new CommentFailedException("父级评论不是一级评论");
            }
        }
        //3.专业回答校验
        // 3.1 校验 answerId 是否符合分区规则（专业区必填，生活区禁止传）
        commentZonePolicy.validateAnswerId(content.getContentType(), commentAddDTO.getAnswerId());
       //3.2 如果 answerId 不为空，校验回答是否存在，校验回答所属问题id与前端传入的一致
        if (commentAddDTO.getAnswerId() != null) {
            AnswerSnapshotVO questionAnswer = answerQueryService.getAnswerSnapshot(commentAddDTO.getAnswerId());
            if (questionAnswer == null) {
                throw new CommentFailedException("回答的问题不存在或已被删除");

            }
            if (!questionAnswer.getQuestionId().equals(commentAddDTO.getContentId())) {
                throw new CommentFailedException("回答的问题id与前端的一致不一致");
            }
        }

        //4.被回复评论校验
        if (commentAddDTO.getReplyCommentId() != null) {
            ContentComment replyComment = commentMapper.selectById(commentAddDTO.getReplyCommentId());
            if (replyComment == null) {
                throw new CommentFailedException("被回复的评论不存在或已被删除");
            }
            // 回复一级评论时 parentId 为 null，其「所属楼层」即 commentId；回复二级时 parentId 为一级评论 id
            Long expectedParentId = replyComment.getParentId() != null
                    ? replyComment.getParentId()
                    : replyComment.getCommentId();
            if (!Objects.equals(expectedParentId, commentAddDTO.getParentId())) {
                throw new CommentFailedException("必须在同一层级评论中回复");
            }
            // 校验 contentId 一致性（防止跨内容回复）
            if (!replyComment.getContentId().equals(contentId)) {
                throw new CommentFailedException("不能跨内容回复评论");
            }
            // 校验 answerId 一致性（防止跨回答回复）
            if (commentAddDTO.getAnswerId() != null) {
                if (!replyComment.getAnswerId().equals(commentAddDTO.getAnswerId())) {
                    throw new CommentFailedException("不能跨回答回复评论");
                }
            }
        }

        // C-4：前端 @ 卡片标记仅作观测留痕（事件侧判定见 CommentAuditServiceImpl）
        if (Boolean.TRUE.equals(commentAddDTO.getMentionBot())) {
            log.info(
                    "评论携带 bot mention 标记，userId={}，contentId={}",
                    userId,
                    contentId
            );
        }

        //5.插入评论表（AI 云审核：入库为待审）
        ContentComment contentComment = ContentComment.builder()
                .contentId(contentId)//评论的内容id
                .answerId(commentAddDTO.getAnswerId())//回答的问题id
                .parentId(commentAddDTO.getParentId())//父级评论id
                .replyCommentId(commentAddDTO.getReplyCommentId())//被回复的评论id
                .replyUserId(commentAddDTO.getReplyUserId())//被回复的用户id
                .userId(userId)//评论的用户id
                .content(commentAddDTO.getContent())//评论内容
                .auditStatus(AuditStatus.PENDING.getCode())// AI 审核：待审
                // .auditStatus(1)// 原：默认通过审核
                .likeCount(0)//默认点赞数为0
                .build();


        commentMapper.insert(contentComment);


        Long commentId = contentComment.getCommentId();
        //6.写入图片表，并收集有效图片 URL 供 AI 图片审核
        List<String> imageUrls = new ArrayList<>();
        if (commentAddDTO.getImageUrls() != null && !commentAddDTO.getImageUrls().isEmpty()) {
            // 限制图片数量最多 maxImages 张
            int maxImages = commentZonePolicy.getMaxImages(content.getContentType());
            if (commentAddDTO.getImageUrls().size() > maxImages) {
                throw new CommentFailedException("图片数量超过"+maxImages+"张");
            }

            List<CommentImage> images = new ArrayList<>();
            int sort = 0;

            // 遍历前端传入的图片 URL 列表
            for (String imageUrl : commentAddDTO.getImageUrls()) {
                // 过滤空字符串和 null
                if (imageUrl == null || imageUrl.trim().isEmpty()) {
                    continue;
                }

                // 校验 URL 格式（可选，防止脏数据）
                if (!imageUrl.trim().startsWith("http://") && !imageUrl.trim().startsWith("https://")) {
                    throw new CommentFailedException("图片 URL 格式不正确");
                }

                CommentImage commentImage = CommentImage.builder()
                        .commentId(contentComment.getCommentId())//评论id
                        .imageUrl(imageUrl.trim())//图片url
                        .sort(sort++)//排序
                        .createTime(LocalDateTime.now())//创建时间
                        .build();
                images.add(commentImage);
                imageUrls.add(imageUrl.trim());
            }

            // 只有有效图片才插入
            if (!images.isEmpty()) {
                commentMapper.insertCommentImagesBatch(images);
            }
        }


        if (commentId == null) {
            throw new CommentFailedException("评论发布失败");
        }

        // 用户新增评论成功：画像行为事件与评论写入同一个事务提交（D2）。
        // 管理员删除/审核驳回路径不经过此处，不重复发事件。
        feedEventProducer.createUserBehaviorEvent(userId, contentId, "COMMENT");

        // 7. 审核开启时，评论、图片和审核 Outbox 在同一个事务中提交。
        if (shouldModerateComment()) {
            commentEventProducer.createCommentModerationEvent(contentComment, imageUrls);
        } else if (isAutoApproveWhenModerationDisabled()) {
            // 审核关闭且策略为 APPROVED 时，直接加入当前事务完成自动通过。
            commentAuditService.approveComment(commentId);
        }

        //8.返回评论id
        return commentId;

    }

    /** 全局开关 + 评论类型开关均开启时才走 AI 审核；bot 评论强制机审（总开关与评论开关均不豁免）。 */
    boolean shouldModerateComment() {
        // C-6：bot 来源评论强制机审——在总开关之前判定，任何开关组合下 bot 回复都过二审
        if (isBotUser(BaseContext.getCurrentId())) {
            return true;
        }
        if (!moderationProperties.isEnabled()) {
            return false;
        }
        AliyunModerationProperties.TargetConfig commentConfig = getCommentTargetConfig();
        return commentConfig != null && commentConfig.isEnabled();
    }

    /** C-6：判定评论作者是否 bot 系统账号。 */
    boolean isBotUser(Long userId) {
        return userId != null
                && userId.equals(quantabotProperties.getBotUserId());
    }

    /** 评论 AI 关闭时默认 policy=APPROVED，避免评论堆积人工审核 */
    private boolean isAutoApproveWhenModerationDisabled() {
        AliyunModerationProperties.TargetConfig commentConfig = getCommentTargetConfig();
        String policy = commentConfig != null ? commentConfig.getDisabledPolicy() : "APPROVED";
        return "APPROVED".equalsIgnoreCase(policy);
    }

    private AliyunModerationProperties.TargetConfig getCommentTargetConfig() {
        AliyunModerationProperties.Targets targets = moderationProperties.getTargets();
        return targets != null ? targets.getComment() : null;
    }

    /**
     * 校验操作者权限后软删除评论及其回复，并在同一事务中更新派生计数和重建事件。
     *
     * @param commentId 要删除的评论 ID
     */
    @Transactional
    @Override
    public void deleteComment(Long commentId) {

        //1.校验评论是否存在
        ContentComment comment = commentMapper.selectById(commentId);
        if (comment == null) {
            throw new CommentFailedException("评论不存在");
        }

//        //获取评论用户ID与当前登录用户ID是否一致
//        Long userId = comment.getUserId();
//        Long currentUserId = BaseContext.getCurrentId();
//        if (!userId.equals(currentUserId)) {
//            throw new CommentFailedException("您没有权限删除该评论");
//        }
//2. 获取当前登录用户 ID
        Long currentUserId = BaseContext.getCurrentId();

//3. 权限校验：评论本人 / 题主 / 答主 均可删除
        Long commentAuthorId = comment.getUserId();
        boolean isCommentAuthor = commentAuthorId.equals(currentUserId);
        boolean isContentAuthor = false;  // 是否题主
        boolean isAnswerAuthor = false;    // 是否答主

// 3.1 如果是评论本人，直接允许删除
        if (isCommentAuthor) {
            // 权限通过，继续执行删除逻辑
        } else {
            // 3.2 查询内容信息，判断是否为题主
            ContentSnapshotVO content = contentQueryService.getContentSnapshot(comment.getContentId());
            if (content != null && content.getPublishUserId().equals(currentUserId)) {
                isContentAuthor = true;
            }

            // 3.3 如果是专业区评论，查询回答信息，判断是否为答主
            if (!isContentAuthor && comment.getAnswerId() != null) {
                AnswerSnapshotVO answer = answerQueryService.getAnswerSnapshot(comment.getAnswerId());
                if (answer != null && answer.getUserId().equals(currentUserId)) {
                    isAnswerAuthor = true;
                }
            }

            // 3.4 如果既不是评论本人，也不是题主或答主，拒绝删除
            if (!isContentAuthor && !isAnswerAuthor) {
                throw new CommentFailedException("您没有权限删除该评论（仅评论本人、题主或答主可删除）");
            }
        }
        //判断是一级评论还是二级评论
        if (comment.getParentId() == null) {
            //一级评论
            deleteFirstComment(comment);
        } else {
            //二级评论
            deleteReplyComment(comment);
        }

        // 评论删除和热度 Outbox 在同一个事务中提交。
        feedEventProducer.createHotScoreRecalculateEvent(comment.getContentId(), "COMMENT_DELETE");
        createCommentSearchEvents(comment, "COMMENT_DELETE");

    }

    /**
     * 清理指定内容下的评论关联数据并软删除评论。
     *
     * <p>该方法只处理评论域自身数据，调用方可将其加入内容删除事务。</p>
     */
    @Transactional
    @Override
    public void deleteByContentId(Long contentId) {
        if (contentId == null) {
            throw new CommentFailedException("contentId 不能为空");
        }
        commentMapper.deleteContentCommentImages(contentId);
        commentInteractionService.deleteByContentId(contentId);
        commentMapper.softDeleteContentComment(contentId);
    }

    /** 评论数变化后，帖子搜索文档和所属回答搜索文档都需要按 MySQL 最新值重建。 */
    private void createCommentSearchEvents(ContentComment comment, String triggerType) {
        searchEventProducer.createSearchReconcileEvent(ModerationTargetType.CONTENT.name(), comment.getContentId(), triggerType);
        if (comment.getAnswerId() != null) {
            searchEventProducer.createSearchReconcileEvent(ModerationTargetType.ANSWER.name(), comment.getAnswerId(), triggerType);
        }
    }

    // 单个评论点赞缓存 key
    private String buildCommentLikedKey(Long commentId) {
        return RedisConstants.COMMENT_LIKED_KEY + commentId;
    }


    // 删除评论点赞缓存
    private void clearCommentLikeCacheAfterCommit(Collection<Long> commentIds) {
        if (commentIds == null || commentIds.isEmpty()) {
            return;
        }
        List<String> keys = commentIds.stream()
                .filter(Objects::nonNull)
                .map(this::buildCommentLikedKey)
                .toList();

        if (keys.isEmpty()) {
            return;
        }

        // 事务提交后再删 Redis，避免事务回滚导致缓存被误删
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    stringRedisTemplate.delete(keys);
                }
            });
        } else {
            stringRedisTemplate.delete(keys);
        }
    }


    // 删除回复评论
    private void deleteReplyComment(ContentComment comment) {
        //软删除二级评论
        commentMapper.softDeleteById(comment.getCommentId());

        //删除二级评论的图片（物理）
        commentMapper.deleteCommentImages(comment.getCommentId());
        //删除二级评论的点赞记录（物理）
        commentInteractionService.deleteByCommentId(comment.getCommentId());
        //更新内容表评论数
        commentCounterService.changeCommentCount(comment.getContentId(), -1);
        contentDetailCacheInvalidator.evictAfterCommit(comment.getContentId(), "comment-reply-delete");
        //更新回答表评论数（仅专业区评论需要）
        if (comment.getAnswerId() != null) {
            int updateCount = commentCounterService.changeAnswerCommentCount(comment.getAnswerId(), -1);
            if (updateCount != 1) {
                throw new CommentFailedException("删除回答评论数失败");
            }
        }
        //删除二级评论的点赞缓存
        clearCommentLikeCacheAfterCommit(Collections.singletonList(comment.getCommentId()));

    }

    private void deleteFirstComment(ContentComment comment) {
        //查询一级评论的所有回复评论id
        List<Long> replyCommentIds = commentMapper.selectReplyIdsByParentId(comment.getCommentId());


        if (!replyCommentIds.isEmpty()) {
            //    删除所有回复评论（软）
            commentMapper.softDeleteRepliesByCommentIds(replyCommentIds);


            //    删除回复的图片（物理）
            commentMapper.deleteCommentImagesByCommentIds(replyCommentIds);

            //    删除回复的点赞记录（物理）
            commentInteractionService.deleteByCommentIds(replyCommentIds);
        }
        //更新内容表评论数
        int totalDeleteCount = 1 + replyCommentIds.size();
        commentCounterService.changeCommentCount(comment.getContentId(), -totalDeleteCount);
        contentDetailCacheInvalidator.evictAfterCommit(comment.getContentId(), "comment-delete");

        // 6. 更新回答表评论数（仅专业区评论需要）
        if (comment.getAnswerId() != null) {
            commentCounterService.changeAnswerCommentCount(comment.getAnswerId(), -totalDeleteCount);
        }


        //    删除一级评论图片（物理）

        commentMapper.deleteCommentImages(comment.getCommentId());
        //   删除一级评论的点赞记录（物理）

        commentInteractionService.deleteByCommentId(comment.getCommentId());
        //   删除一级评论（软删除）
        commentMapper.softDeleteById(comment.getCommentId());


        //删除一级和所有回复评论的点赞缓存
        List<Long> allDeletedIds = new ArrayList<>();
        allDeletedIds.add(comment.getCommentId());
        if (replyCommentIds != null && !replyCommentIds.isEmpty()) {
            allDeletedIds.addAll(replyCommentIds);
        }
        clearCommentLikeCacheAfterCommit(allDeletedIds);

    }
}
