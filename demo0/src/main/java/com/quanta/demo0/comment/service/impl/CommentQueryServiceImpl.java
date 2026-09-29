package com.quanta.demo0.comment.service.impl;

import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.quanta.demo0.answer.service.AnswerQueryService;
import com.quanta.demo0.answer.vo.AnswerSnapshotVO;
import com.quanta.demo0.comment.dto.CommentPageDTO;
import com.quanta.demo0.comment.dto.ReplyPageDTO;
import com.quanta.demo0.comment.entity.ContentComment;
import com.quanta.demo0.comment.entity.ReplyCountRow;
import com.quanta.demo0.comment.exception.CommentFailedException;
import com.quanta.demo0.comment.service.CommentCounterService;
import com.quanta.demo0.comment.service.CommentQueryService;
import com.quanta.demo0.comment.vo.CommentPageVO;
import com.quanta.demo0.comment.vo.CommentSnapshotVO;
import com.quanta.demo0.comment.mapper.CommentMapper;
import com.quanta.demo0.content.service.ContentQueryService;
import com.quanta.demo0.content.vo.ContentSnapshotVO;
import com.quanta.demo0.interaction.service.CommentInteractionService;
import com.quanta.demo0.platform.security.context.BaseContext;
import com.quanta.demo0.platform.security.properties.QuantabotProperties;
import com.quanta.demo0.user.service.UserQueryService;
import com.quanta.demo0.user.vo.UserAuthInfoVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 评论查询服务实现。
 *
 * 负责评论与回复分页查询、点赞状态和用户展示信息组装，不承担写操作。
 */
@Service
@RequiredArgsConstructor
public class CommentQueryServiceImpl implements CommentQueryService {

    private static final DateTimeFormatter DATE_TIME_FORMATTER
            = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final CommentMapper commentMapper;
    private final ContentQueryService contentQueryService;
    private final AnswerQueryService answerQueryService;
    private final UserQueryService userQueryService;
    private final CommentInteractionService commentInteractionService;
    private final CommentCounterService commentCounterService;
    private final QuantabotProperties quantabotProperties;

    /** 返回评论事实快照，供其他域通过查询端口读取。 */
    @Override
    public CommentSnapshotVO getCommentSnapshot(Long commentId) {
        return commentCounterService.getCommentSnapshot(commentId);
    }

    /**
     * 查询一级评论、回复预览、点赞状态和用户展示信息。
     *
     * @param commentPageDTO 评论分页请求
     * @return 评论分页结果
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
        ContentSnapshotVO content = contentQueryService.getContentSnapshot(commentPageDTO.getContentId());
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
            AnswerSnapshotVO qa = answerQueryService.getAnswerSnapshot(commentPageDTO.getAnswerId());
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


        Map<Long, UserAuthInfoVO> userInfoMap = queryUserInfoMap(allUserIds);


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
            AnswerSnapshotVO answer = answerQueryService.getAnswerSnapshot(commentPageDTO.getAnswerId());
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
            // C-4：AI 标识透出（前端渲染“AI”角标的依据）
            firstMap.put("isBot", isBotUser(first.getUserId()));
            // 【新增】身份标识字段
            // 判断是否为题主（问题发布者）
            firstMap.put("isContentAuthor", contentAuthorId != null && first.getUserId().equals(contentAuthorId));
            // 判断是否为答主（回答发布者）
            firstMap.put("isAnswerAuthor", answerAuthorId != null && first.getUserId().equals(answerAuthorId));

            UserAuthInfoVO firstUser = userInfoMap.get(first.getUserId());
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
    /**
     * 查询指定一级评论下的回复，并组装点赞和用户信息。
     *
     * @param replyPageDTO 回复分页请求
     * @return 回复分页结果
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
        Map<Long, UserAuthInfoVO> userInfoMap = queryUserInfoMap(userIds);
// 6.5 查询题主和答主 ID（用于身份标识）
// 查询内容信息获取题主 ID
        ContentSnapshotVO content = contentQueryService.getContentSnapshot(replyPageDTO.getContentId());
        Long contentAuthorId = content != null ? content.getPublishUserId() : null;

// 查询答主 ID（仅专业区有）
        Long answerAuthorId = null;
        if (parentComment.getAnswerId() != null) {
            AnswerSnapshotVO answer = answerQueryService.getAnswerSnapshot(parentComment.getAnswerId());
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

    //查询用户信息
    private Map<Long, UserAuthInfoVO> queryUserInfoMap(Set<Long> userIds) {
        if (userIds.isEmpty()) {
            return new HashMap<>();
        }
        List<UserAuthInfoVO> userAuthInfos = userQueryService.getUserAuthInfos(new ArrayList<>(userIds));
        return userAuthInfos.stream()
                .collect(Collectors.toMap(
                        UserAuthInfoVO::getUserId,
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
        return commentInteractionService.getLikedCommentIds(userId, commentIds);
    }

    //封装二级评论列表
    private List<Map<String, Object>> buildReplyList(
            List<ContentComment> replies,
            Set<Long> likeCommentIds,
            Map<Long, UserAuthInfoVO> userInfoMap,
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
            // C-4：AI 标识透出（前端渲染“AI”角标的依据）
            replyMap.put("isBot", isBotUser(reply.getUserId()));
            // 【新增】身份标识字段
            replyMap.put("isContentAuthor", contentAuthorId != null && reply.getUserId().equals(contentAuthorId));
            replyMap.put("isAnswerAuthor", answerAuthorId != null && reply.getUserId().equals(answerAuthorId));
            // 发布者信息
            UserAuthInfoVO replyUser = userInfoMap.get(reply.getUserId());
            if (replyUser != null) {
                replyMap.put("avatarUrl", replyUser.getAvatarUrl());
                replyMap.put("nickName", replyUser.getNickName());
                replyMap.put("quantaDepartment", replyUser.getQuantaDepartment());
                replyMap.put("quantaBatch", replyUser.getQuantaBatch());
            }

            // 被回复者信息
            if (reply.getReplyUserId() != null) {
                UserAuthInfoVO beReplyUser = userInfoMap.get(reply.getReplyUserId());
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

    private boolean isBotUser(Long userId) {
        return userId != null
                && userId.equals(quantabotProperties.getBotUserId());
    }
}
