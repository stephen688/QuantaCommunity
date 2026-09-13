package com.quanta.demo0.service.Impl;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.quanta.demo0.annotation.ModerationTargetType;
import com.quanta.demo0.constant.RedisConstants;
import com.quanta.demo0.dto.CommentAddDTO;
import com.quanta.demo0.dto.CommentPageDTO;
import com.quanta.demo0.dto.CommentReportDTO;
import com.quanta.demo0.dto.ReplyPageDTO;
import com.quanta.demo0.entity.*;
import com.quanta.demo0.enums.AuditStatus;
import com.quanta.demo0.enums.NotificationType;
import com.quanta.demo0.exception.CommentFailedException;
import com.quanta.demo0.mapper.CommentMapper;
import com.quanta.demo0.mapper.ContentMapper;
import com.quanta.demo0.mapper.QuestionMapper;
import com.quanta.demo0.mapper.UserMapper;
import com.quanta.demo0.mq.message.NotificationEventMessage;
import com.quanta.demo0.policy.CommentZonePolicy;
import com.quanta.demo0.properties.AliyunModerationProperties;
import com.quanta.demo0.service.CommentAuditService;
import com.quanta.demo0.service.CommentService;
import com.quanta.demo0.service.OutboxEventService;
import com.quanta.demo0.utils.SensitiveWordChecker;
import com.quanta.demo0.vo.CommentPageVO;
import com.quanta.demo0.vo.LikeResultVO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 评论主服务实现类。
 *
 * 核心职责：
 * 1. 负责评论/回复发布、分页查询、点赞、删除与举报处理；
 * 2. 协调评论审核投递与审核后副作用执行（计数、热度、通知、索引）；
 * 3. 按内容分区策略控制评论权限，保证评论行为符合业务规则。
 *
 * 设计说明：
 * - 发布主流程在事务内完成，异步副作用通过 afterCommit 触发；
 * - 结合 Redis 与数据库实现高频交互场景下的读写性能平衡。
 */
@Service
@Slf4j
public class CommentServiceImpl implements CommentService {
    private static final DateTimeFormatter DATE_TIME_FORMATTER
            = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    @Autowired
    private CommentMapper commentMapper;
    @Autowired
    private ContentMapper contentMapper;
    @Autowired
    private QuestionMapper questionMapper;

    @Autowired
    private UserMapper userMapper;
    @Autowired
    private SensitiveWordChecker sensitiveWordChecker;

   @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private CommentZonePolicy commentZonePolicy;
    @Autowired
    private AliyunModerationProperties moderationProperties;
    @Autowired
    private CommentAuditService commentAuditService;
    @Autowired
    private OutboxEventService outboxEventService;

    /**
     * 发送评论
     * 1. 校验内容与敏感词 → 2. 校验父级/回答/回复关系
     * 3. 插入评论（待审）与图片 → 4. 同一事务写入审核 Outbox 或按配置自动通过
     * 计数/热度/ES/通知迁移到 CommentAuditService.approveComment
     */
    @Transactional
    @Override
    public Long sendComment(CommentAddDTO commentAddDTO) {
        //获取用户id
        Long userId = BaseContext.getCurrentId();


        //1.校验内容（是否不为空且未被删除，是否通过审核，是否超过500，是否有敏感词）
        Long contentId = commentAddDTO.getContentId();
        Content content = contentMapper.selectById(contentId);
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
            QuestionAnswer questionAnswer = questionMapper.selectById(commentAddDTO.getAnswerId());
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

        // 7. 审核开启时，评论、图片和审核 Outbox 在同一个事务中提交。
        if (shouldModerateComment()) {
            outboxEventService.createCommentModerationEvent(contentComment, imageUrls);
        } else if (isAutoApproveWhenModerationDisabled()) {
            // 审核关闭且策略为 APPROVED 时，直接加入当前事务完成自动通过。
            commentAuditService.approveComment(commentId);
        }

        //8.返回评论id
        return commentId;

    }

    /** 全局开关 + 评论类型开关均开启时才走 AI 审核 */
    private boolean shouldModerateComment() {
        if (!moderationProperties.isEnabled()) {
            return false;
        }
        AliyunModerationProperties.TargetConfig commentConfig = getCommentTargetConfig();
        return commentConfig != null && commentConfig.isEnabled();
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
     * 查询评论分页列表
     * <p>
     * 【核心步骤】
     * 1. 参数校验 → 2. 内容校验 → 3. 回答校验 → 4. PageHelper 分页查询一级评论
     * 5. 提取父评论 ID 列表 → 6. 批量查询回复数量（避免 N+1 问题）
     * 7. 批量查询前 3 条回复（窗口函数）→ 8. 查询点赞状态（登录用户）
     * 9. 批量查询用户信息 → 10. 组装评论列表 → 11. 判断是否有更多
     * 12. 封装 VO 并返回
     * <p>
     * 【技术要点】
     * - PageHelper 物理分页
     * - 批量查询避免 N+1 问题
     * - Stream 流处理数据
     * - 窗口函数查询前 N 条
     * - Map 快速查找
     * <p>
     * 【性能优化】
     * - 批量查询优化
     * - 按需查询（点赞状态）
     * - 内存缓存（userInfoMap 等）
     */
    @Override
    public CommentPageVO commentPage(CommentPageDTO commentPageDTO) {

        //1. 参数校验
        if (commentPageDTO == null || commentPageDTO.getContentId() == null) {
            throw new CommentFailedException("contentId 不能为空");
        }
        int pageNum = (commentPageDTO.getPageNum() == null || commentPageDTO.getPageNum() <= 0) ? 1 : commentPageDTO.getPageNum();
        int pageSize = (commentPageDTO.getPageSize() == null || commentPageDTO.getPageSize() <= 0) ? 10 : commentPageDTO.getPageSize();
        int sortType = (commentPageDTO.getSortType() == null ||
                (commentPageDTO.getSortType() != 1 && commentPageDTO.getSortType() != 2))
                ? 1 : commentPageDTO.getSortType();


        //2. 查询内容是否存在，且审核状态为通过
        Content content = contentMapper.selectById(commentPageDTO.getContentId());
        if (content == null) {
            throw new CommentFailedException("内容不存在");
        }
        if (content.getAuditStatus() != 1) {
            throw new CommentFailedException("内容审核未通过");
        }
        //3.回答校验
        if (content.getContentType() != null && content.getContentType() == 2 && commentPageDTO.getAnswerId() == null) {
            throw new CommentFailedException("专业问答评论查询必须传 answerId");
        }
        if (commentPageDTO.getAnswerId() != null) {
            QuestionAnswer qa = questionMapper.selectById(commentPageDTO.getAnswerId());
            if (qa == null || !commentPageDTO.getContentId().equals(qa.getQuestionId())) {
                throw new CommentFailedException("回答与问题不匹配");
            }

        }
        //4.分页查询一级评论
        PageHelper.startPage(pageNum, pageSize);
        Page<ContentComment> page = commentMapper.selectFirstLevelComments(commentPageDTO.getContentId(), commentPageDTO.getAnswerId(), sortType);

        if (page.isEmpty()) {//没有一级评论，返回空列表
            return CommentPageVO.builder()
                    .pageNum(pageNum)
                    .pageSize(pageSize)
                    .total(page.getTotal())
                    .hasMore(false)
                    .list(Collections.emptyList())
                    .build();
        }


        List<ContentComment> firstLevelComments = page.getResult();

        // 实际示例：
        // firstLevelComments = [
        //   {commentId: 1, userId: 101, content: "评论 1", likeCount: 5},
        //   {commentId: 2, userId: 102, content: "评论 2", likeCount: 3},
        //   {commentId: 3, userId: 103, content: "评论 3", likeCount: 8}
        // ]

        //5.提取父评论ids
        List<Long> parentIds = firstLevelComments.stream().
                map(ContentComment::getCommentId).
                toList();

        //最终结果示例：
        // parentIds = [1, 2, 3]

        // 6.批量查询回复数量（目的：为一级评论添加回复数量）
        Map<Long, Long> replyCountsMap = commentMapper.selectReplyCountsByParentIds(parentIds)
                .stream()
                .collect(Collectors.toMap(
                        ReplyCountRow::getParentId,
                        row -> row.getReplyCount() == null ? 0 : row.getReplyCount(),
                        (a, b) -> a
                ));
        ;

        // 最终结果示例：
        // replyCountMap = {
        //     1: 5,    // 评论 ID 为 1 的一级评论有 5 条回复
        //     2: 3,    // 评论 ID 为 2 的一级评论有 3 条回复
        //     3: 10    // 评论 ID 为 3 的一级评论有 10 条回复
        // }


        //7. 批量查询一级评论下的前 3 条回复（内联）
        Map<Long, List<ContentComment>> replyGroups = commentMapper.selectTopRepliesByParentIds(parentIds, 3)
                .stream()
                .collect(Collectors.groupingBy(ContentComment::getParentId));
        // 最终结果：
        // replyGroups = {
        //   1: [
        //     {commentId: 101, parentId: 1, content: "回复 1"},
        //     {commentId: 102, parentId: 1, content: "回复 2"},
        //     {commentId: 103, parentId: 1, content: "回复 3"}
        //   ],
        //   2: [
        //     {commentId: 104, parentId: 2, content: "回复 4"}
        //   ],
        //   3: [
        //     {commentId: 105, parentId: 3, content: "回复 5"},
        //     {commentId: 106, parentId: 3, content: "回复 6"}
        //   ]
        // }


        //8.获取所有二级评论ids
        List<Long> secondLevelCommentIds = replyGroups.values().stream()
                .flatMap(List::stream)//将二级评论列表展开为流
                .map(ContentComment::getCommentId)//提取二级评论id
                .filter(Objects::nonNull)//过滤掉空值
                .toList();//转换为列表

        //组装总评论ids
        List<Long> allCommentIds = new ArrayList<>(parentIds);
        allCommentIds.addAll(secondLevelCommentIds);


        //8.查询点赞状态
        Set<Long> likeCommentIds = queryCommentLikeIds(allCommentIds);
        //9.查询用户信息（发布者+被回复用户）
        Set<Long> allUserIds = new HashSet<>();
        firstLevelComments.forEach(//遍历一级评论,提取发布者和被回复用户id
                comment -> {
                    if (comment.getReplyUserId() != null) allUserIds.add(comment.getReplyUserId());
                    if (comment.getUserId() != null) allUserIds.add(comment.getUserId());
                }
        );
        replyGroups.values().forEach(//遍历二级评论,提取发布者和被回复用户id
                replyList -> {
                    replyList.forEach(
                            reply -> {
                                if (reply.getReplyUserId() != null) allUserIds.add(reply.getReplyUserId());
                                if (reply.getUserId() != null) allUserIds.add(reply.getUserId());
                            });
                });


        //最终用户ids示例：
        // allUserIds = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10}


        Map<Long, UserAuthInfo> userInfoMap = queryUserInfoMap(allUserIds);


        //最终用户信息示例：
        // userInfoMap = {
        //     1: 用户 1,
        //     2: 用户 2,
        //     3: 用户 3,
        //     4: 用户 4,
        //     5: 用户 5,
        //     6: 用户 6,
        //     7: 用户 7,
        //     8: 用户 8,
        //     9: 用户 9,
        //     10: 用户 10
        // }
        //用于身份标识
        Long contentAuthorId = content.getPublishUserId();  // 题主 ID
        Long answerAuthorId = null;  // 答主 ID（仅专业区有）
        if (commentPageDTO.getAnswerId() != null) {
            QuestionAnswer answer = questionMapper.selectById(commentPageDTO.getAnswerId());
            if (answer != null) {
                answerAuthorId = answer.getUserId();
            }
        }
        //10.构建评论列表

        //一级
        List<Map<String, Object>> commentList = new ArrayList<>();

        for (ContentComment first : firstLevelComments) {
            Map<String, Object> firstMap = new HashMap<>();
            firstMap.put("commentId", first.getCommentId());
            firstMap.put("userId", first.getUserId());
            firstMap.put("contentId", first.getContentId());
            firstMap.put("answerId", first.getAnswerId());
            firstMap.put("parentId", first.getParentId());
            firstMap.put("replyUserId", first.getReplyUserId());
            firstMap.put("content", first.getContent());
            firstMap.put("createTime", DATE_TIME_FORMATTER.format(first.getCreateTime()));
            firstMap.put("likeCount", first.getLikeCount());
            firstMap.put("replyCount", replyCountsMap.getOrDefault(first.getCommentId(), 0L));
            firstMap.put("isLiked", likeCommentIds.contains(first.getCommentId()));
            // 【新增】身份标识字段
            // 判断是否为题主（问题发布者）
            firstMap.put("isContentAuthor", contentAuthorId != null && first.getUserId().equals(contentAuthorId));
            // 判断是否为答主（回答发布者）
            firstMap.put("isAnswerAuthor", answerAuthorId != null && first.getUserId().equals(answerAuthorId));

            UserAuthInfo firstUser = userInfoMap.get(first.getUserId());
            if (firstUser != null) {
                firstMap.put("userId", firstUser.getUserId());
                firstMap.put("avatarUrl", firstUser.getAvatarUrl());
                firstMap.put("nickName", firstUser.getNickName());
                firstMap.put("quantaBatch", firstUser.getQuantaBatch());
            }


            // 构建二级评论列表（ 可复用方法）
            List<ContentComment> replies = replyGroups.getOrDefault(
                    first.getCommentId(),
                    Collections.emptyList()
            );
            List<Map<String, Object>> replyList = buildReplyList(replies, likeCommentIds, userInfoMap,contentAuthorId,answerAuthorId);
            firstMap.put("replyList", replyList);

            //添加一级评论到列表
            commentList.add(firstMap);
        }
        //11.判断是否有更多数据
        boolean hasMore = (long) pageNum * pageSize < page.getTotal();//如果当前页码乘以每页记录数小于总记录数，说明还有更多数据

        //12.封装返回
        return CommentPageVO.builder()
                .pageNum(pageNum)
                .pageSize(pageSize)
                .total(page.getTotal())
                .hasMore(hasMore)
                .list(commentList)
                .build();
    }

    /**
     * 查询回复列表
     */
    @Override
    public CommentPageVO replyPage(ReplyPageDTO replyPageDTO) {
        //1.参数校验
        if (replyPageDTO.getParentCommentId() == null || replyPageDTO.getContentId() == null) {
            throw new CommentFailedException("一级评论ID和内容ID不能为空");
        }
        int pageNum = replyPageDTO.getPageNum() == null ? 1 : replyPageDTO.getPageNum();
        int pageSize = replyPageDTO.getPageSize() == null ? 10 : replyPageDTO.getPageSize();
        int sortType = (replyPageDTO.getSortType() == null ||
                (replyPageDTO.getSortType() != 1 && replyPageDTO.getSortType() != 2))
                ? 1 : replyPageDTO.getSortType();

        //2.查询一级评论

        ContentComment parentComment = commentMapper.selectById(replyPageDTO.getParentCommentId());
        if (parentComment == null) {
            throw new CommentFailedException("一级评论不存在");
        }
        if (parentComment.getParentId() != null) {
            throw new CommentFailedException("一级评论不能回复二级评论");
        }
        if (!parentComment.getContentId().equals(replyPageDTO.getContentId())) {
            throw new CommentFailedException("一级评论内容ID与回复内容ID不一致");
        }
        // 校验 answerId 一致性（防止跨回答查询回复）
        if (parentComment.getAnswerId() != null) {
            // 专业区评论：请求必须传 answerId，且与父评论的 answerId 一致
            if (replyPageDTO.getAnswerId() == null) {
                throw new CommentFailedException("专业区评论查询回复必须传 answerId");
            }
            if (!parentComment.getAnswerId().equals(replyPageDTO.getAnswerId())) {
                throw new CommentFailedException("请求的 answerId 与父评论所属回答不一致");
            }

        }
        //3.分页查询二级评论
        PageHelper.startPage(pageNum, pageSize);
        Page<ContentComment> page = commentMapper.selectByParentId(replyPageDTO.getParentCommentId(), sortType);
        if (page.isEmpty()) {
            return CommentPageVO.builder()
                    .pageNum(pageNum)
                    .pageSize(pageSize)
                    .total(page.getTotal())
                    .hasMore(false)
                    .list(Collections.emptyList())
                    .build();
        }
        List<ContentComment> replyComments = page.getResult();


        //4.计算分页信息
        boolean hasMore = (long) pageNum * pageSize < page.getTotal();//如果当前页码乘以每页记录数小于总记录数，说明还有更多数据
        Long total = page.getTotal();
        int totalPages = (int) Math.ceil(total / (double) pageSize);//计算总页数

        //5.查询点赞信息
        List<Long> likeCommentIds = replyComments.stream()
                .map(ContentComment::getCommentId)
                .toList();
        Set<Long> likeCommentIdsSet = queryCommentLikeIds(likeCommentIds);

        //6.查询用户信息
        Set<Long> userIds = new HashSet<>();
        replyComments.forEach(replyComment -> {
            if (replyComment.getUserId() != null) userIds.add(replyComment.getUserId());
            if (replyComment.getReplyUserId() != null) userIds.add(replyComment.getReplyUserId());
        });
        Map<Long, UserAuthInfo> userInfoMap = queryUserInfoMap(userIds);
// 6.5 查询题主和答主 ID（用于身份标识）
// 查询内容信息获取题主 ID
        Content content = contentMapper.selectById(replyPageDTO.getContentId());
        Long contentAuthorId = content != null ? content.getPublishUserId() : null;

// 查询答主 ID（仅专业区有）
        Long answerAuthorId = null;
        if (parentComment.getAnswerId() != null) {
            QuestionAnswer answer = questionMapper.selectById(parentComment.getAnswerId());
            if (answer != null) {
                answerAuthorId = answer.getUserId();
            }
        }

        //7.组装回复列表
        List<Map<String, Object>> replyList = buildReplyList(replyComments, likeCommentIdsSet, userInfoMap,contentAuthorId,answerAuthorId);
        //8.返回回复列表
        return CommentPageVO.builder()
                .pageNum(pageNum)
                .pageSize(pageSize)
                .total(total)
                .hasMore(hasMore)
                .list(replyList)
                .build();
    }

    /**
     * 举报评论
     * 【核心步骤】
     * 1. 参数校验 → 2. 查询评论是否存在 → 3. 校验举报类型
     * 4. 检查是否重复举报 → 5. 插入举报记录 → 6. 返回结果
     * 【规则说明】
     * - 同一用户对同一评论只能举报一次（有效记录 is_deleted=0）
     * - 举报类型必填（1-垃圾广告 2-人身攻击 3-违规内容 4-虚假信息 5-其他）
     * - 举报后状态默认为"待处理"（status=0）
     * - 举报记录支持软删除（is_deleted=1）
     * @param commentReportDTO 举报数据（commentId、reportType）
     */
    @Transactional
    @Override
    public void reportComment(CommentReportDTO commentReportDTO) {

        //1.参数校验
        if (commentReportDTO.getCommentId() == null) {
            throw new CommentFailedException("评论ID不能为空");
        }
        if (commentReportDTO.getReportType() == null ||
                commentReportDTO.getReportType() < 1 ||
                commentReportDTO.getReportType() > 5) {
            throw new CommentFailedException("举报类型不合法（1-垃圾广告 2-人身攻击 3-违规内容 4-虚假信息 5-其他）");
        }
        //2.查询评论是否存在
        ContentComment comment = commentMapper.selectById(commentReportDTO.getCommentId());
        if (comment == null) {
            throw new CommentFailedException("评论不存在");
        }
        // 3. 检查是否重复举报（同一用户对同一评论只能有一条有效举报记录）
        Long reporterId = BaseContext.getCurrentId();
        CommentReport existingReport = commentMapper.selectValidReportByCommentAndUser(commentReportDTO.getCommentId(), reporterId);
        if (existingReport != null) {
            throw new CommentFailedException("您已举报过该评论，请勿重复举报");
        }

        //4.插入举报记录
        CommentReport report = CommentReport.builder()
                .commentId(commentReportDTO.getCommentId())
                .reportType(commentReportDTO.getReportType())
                .reporterId(reporterId)
                .status(0) // 待处理
                .isDeleted(0) // 有效记录
                .createTime(LocalDateTime.now())
                .updateTime(LocalDateTime.now())
                .build();
        commentMapper.insertCommentReport(report);
    }


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
            Content content = contentMapper.selectById(comment.getContentId());
            if (content != null && content.getPublishUserId().equals(currentUserId)) {
                isContentAuthor = true;
            }

            // 3.3 如果是专业区评论，查询回答信息，判断是否为答主
            if (!isContentAuthor && comment.getAnswerId() != null) {
                QuestionAnswer answer = questionMapper.selectById(comment.getAnswerId());
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
        outboxEventService.createHotScoreRecalculateEvent(comment.getContentId(), "COMMENT_DELETE");
        createCommentSearchEvents(comment, "COMMENT_DELETE");

    }

    /**
     * 设置评论点赞状态。
     * MySQL 点赞明细表是状态和计数变更的最终依据，Redis 仅用于提交后的状态同步。
     */
    @Transactional
    @Override
    public LikeResultVO likeComment(Long commentId, boolean targetLiked) {
        //1.参数校验
        if (commentId == null) {
            throw new CommentFailedException("评论ID不能为空");
        }
        //2.校验评论是否存在
        ContentComment comment = commentMapper.selectById(commentId);
        if (comment == null) {
            throw new CommentFailedException("评论不存在");
        }
        //3.获取用户ID
        Long userId = BaseContext.getCurrentId();

        //4.根据请求目标状态操作 MySQL 点赞明细
        String key = RedisConstants.COMMENT_LIKED_KEY + commentId;
        boolean changed = false;
        boolean isLiked = targetLiked;
        if (targetLiked) {
            int inserted = commentMapper.insertCommentLikes(commentId, userId);

            // 只有真正新增点赞明细，才增加评论点赞数
            if (inserted == 1) {
                int rows = commentMapper.updateLikeCount(commentId, 1);
                if (rows != 1) {
                    throw new CommentFailedException("点赞失败");
                }
                changed = true;
            }
        } else {
            int deleted = commentMapper.deleteCommentLikeByUser(commentId, userId);

            // 只有真正删除点赞明细，才减少评论点赞数
            if (deleted == 1) {
                int rows = commentMapper.updateLikeCount(commentId, -1);
                if (rows != 1) {
                    throw new CommentFailedException("取消点赞失败");
                }
                changed = true;
            }
        }
     // 只有本次真正新增点赞，并且不是点赞自己的评论，才创建通知。
        if (changed && isLiked && !comment.getUserId().equals(userId)) {
            NotificationEventMessage likeNotification = NotificationEventMessage.builder()
                    .recipientUserId(comment.getUserId())
                    .actorUserId(userId)
                    .type(NotificationType.LIKE_COMMENT.getCode())
                    .content("点赞了你的评论")
                    .payload(Map.of(
                            "contentId", comment.getContentId(),
                            "commentId", commentId
                    ))
                    .build();

            // 评论点赞和通知 Outbox 同时提交，任何一步失败都会一起回滚。
            outboxEventService.createNotificationEvent(likeNotification, ModerationTargetType.COMMENT.name(), commentId);
        }
        //5.事务提交后再同步 Redis（失败不影响主事务）
        final boolean stateChanged = changed;
        if (stateChanged && TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    try {
                        if (isLiked) {
                            stringRedisTemplate.opsForZSet().add(key, userId.toString(), System.currentTimeMillis());
                        } else {
                            stringRedisTemplate.opsForZSet().remove(key, userId.toString());
                        }
                    } catch (Exception e) {
                        log.error("评论点赞缓存同步失败，businessType=COMMENT_LIKE, userId={}, targetId={}, targetState={}",
                                userId, commentId, isLiked, e);
                    }
                }
            });
        }
        //6.获取最新评论点赞总数
        ContentComment latest = commentMapper.selectById(commentId);
        int likeCount = latest.getLikeCount() == null ? 0 : latest.getLikeCount();

        //7.返回点赞结果
        return LikeResultVO.builder()
                .isLiked(isLiked)
                .likedCount(likeCount)
                .build();
    }

    /** 评论数变化后，帖子搜索文档和所属回答搜索文档都需要按 MySQL 最新值重建。 */
    private void createCommentSearchEvents(ContentComment comment, String triggerType) {
        outboxEventService.createSearchReconcileEvent(ModerationTargetType.CONTENT.name(), comment.getContentId(), triggerType);
        if (comment.getAnswerId() != null) {
            outboxEventService.createSearchReconcileEvent(ModerationTargetType.ANSWER.name(), comment.getAnswerId(), triggerType);
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
        commentMapper.deleteCommentLikes(comment.getCommentId());
        //更新内容表评论数
        commentMapper.updateCommentCount(comment.getContentId(), -1);
        //更新回答表评论数（仅专业区评论需要）
        if (comment.getAnswerId() != null) {
            int updateCount = questionMapper.updateAnswerCommentCount(comment.getAnswerId(), -1);
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
            commentMapper.deleteCommentLikesByCommentIds(replyCommentIds);
        }
        //更新内容表评论数
        int totalDeleteCount = 1 + replyCommentIds.size();
        commentMapper.updateCommentCount(comment.getContentId(), -totalDeleteCount);

        // 6. 更新回答表评论数（仅专业区评论需要）
        if (comment.getAnswerId() != null) {
            questionMapper.updateAnswerCommentCount(comment.getAnswerId(), -totalDeleteCount);
        }


        //    删除一级评论图片（物理）

        commentMapper.deleteCommentImages(comment.getCommentId());
        //   删除一级评论的点赞记录（物理）

        commentMapper.deleteCommentLikes(comment.getCommentId());
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


    //----------------------方法区-------------------------

    //查询用户信息
    private Map<Long, UserAuthInfo> queryUserInfoMap(Set<Long> userIds) {
        if (userIds.isEmpty()) {
            return new HashMap<>();
        }
        List<UserAuthInfo> userAuthInfos = userMapper.selectUserAuthInfoByIds(new ArrayList<>(userIds));
        return userAuthInfos.stream()
                .collect(Collectors.toMap(
                        UserAuthInfo::getUserId,
                        u -> u,
                        (v1, v2) -> v1
                ));
    }


    //查询点赞信息
    private Set<Long> queryCommentLikeIds(List<Long> commentIds) {
        Long userId = BaseContext.getCurrentId();
        if (userId == null || commentIds.isEmpty()) {
            return new HashSet<>();
        }
        return commentMapper.selectCommentLikeIds(userId, commentIds);
    }

    //封装二级评论列表
    private List<Map<String, Object>> buildReplyList(
            List<ContentComment> replies,
            Set<Long> likeCommentIds,
            Map<Long, UserAuthInfo> userInfoMap,
            Long contentAuthorId,
            Long answerAuthorId
    ) {
        List<Map<String, Object>> replyList = new ArrayList<>();

        for (ContentComment reply : replies) {
            Map<String, Object> replyMap = new HashMap<>();
            replyMap.put("commentId", reply.getCommentId());
            replyMap.put("userId", reply.getUserId());
            replyMap.put("replyUserId", reply.getReplyUserId());
            replyMap.put("content", reply.getContent());
            replyMap.put("createTime", DATE_TIME_FORMATTER.format(reply.getCreateTime()));
            replyMap.put("likeCount", reply.getLikeCount());
            replyMap.put("isLiked", likeCommentIds.contains(reply.getCommentId()));
            // 【新增】身份标识字段
            replyMap.put("isContentAuthor", contentAuthorId != null && reply.getUserId().equals(contentAuthorId));
            replyMap.put("isAnswerAuthor", answerAuthorId != null && reply.getUserId().equals(answerAuthorId));
            // 发布者信息
            UserAuthInfo replyUser = userInfoMap.get(reply.getUserId());
            if (replyUser != null) {
                replyMap.put("avatarUrl", replyUser.getAvatarUrl());
                replyMap.put("nickName", replyUser.getNickName());
                replyMap.put("quantaDepartment", replyUser.getQuantaDepartment());
                replyMap.put("quantaBatch", replyUser.getQuantaBatch());
            }

            // 被回复者信息
            if (reply.getReplyUserId() != null) {
                UserAuthInfo beReplyUser = userInfoMap.get(reply.getReplyUserId());
                if (beReplyUser != null) {
                    replyMap.put("replyAvatarUrl", beReplyUser.getAvatarUrl());
                    replyMap.put("replyNickName", beReplyUser.getNickName());
                    replyMap.put("replyQuantaDepartment", beReplyUser.getQuantaDepartment());
                    replyMap.put("replyQuantaBatch", beReplyUser.getQuantaBatch());
                }
            }

            replyList.add(replyMap);
        }

        return replyList;
    }

}
