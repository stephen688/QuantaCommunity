package com.quanta.demo0.service.Impl;

import com.quanta.demo0.controller.bot.vo.BotCommentChainVO;
import com.quanta.demo0.controller.bot.vo.BotCommentHistoryVO;
import com.quanta.demo0.controller.bot.vo.BotCommentNodeVO;
import com.quanta.demo0.controller.bot.vo.BotCommentTreeVO;
import com.quanta.demo0.controller.bot.vo.BotPostVO;
import com.quanta.demo0.entity.CommentImage;
import com.quanta.demo0.entity.Content;
import com.quanta.demo0.entity.ContentComment;
import com.quanta.demo0.exception.CommentFailedException;
import com.quanta.demo0.mapper.CommentMapper;
import com.quanta.demo0.mapper.ContentMapper;
import com.quanta.demo0.service.BotCommentService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * bot 只读评论实现：只读取审核通过且未删除的评论，并输出稳定的跨服务契约。
 */
@Service
@RequiredArgsConstructor
public class BotCommentServiceImpl implements BotCommentService {

    private static final DateTimeFormatter DATE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int MAX_CHAIN_DEPTH = 50;
    private static final int MAX_PAGE_SIZE = 200;

    private final CommentMapper commentMapper;
    private final ContentMapper contentMapper;

    @Override
    public BotCommentChainVO getChain(Long commentId) {
        ContentComment trigger = commentMapper.selectVisibleById(commentId);
        if (trigger == null) {
            throw new CommentFailedException("评论不存在或不可见");
        }
        Content post = contentMapper.selectById(trigger.getContentId());
        if (post == null) {
            throw new CommentFailedException("帖子不存在或已删除");
        }

        Deque<ContentComment> chainStack = new ArrayDeque<>();
        Set<Long> visitedCommentIds = new HashSet<>();
        ContentComment current = trigger;
        while (current != null
                && visitedCommentIds.add(current.getCommentId())
                && chainStack.size() < MAX_CHAIN_DEPTH) {
            chainStack.push(current);
            Long ancestorId = current.getReplyCommentId() != null
                    ? current.getReplyCommentId()
                    : current.getParentId();
            current = ancestorId == null || ancestorId == 0L
                    ? null
                    : commentMapper.selectVisibleById(ancestorId);
        }

        List<ContentComment> orderedChain = new ArrayList<>(chainStack);
        return BotCommentChainVO.builder()
                .post(BotPostVO.builder()
                        .postId(post.getContentId())
                        .userId(post.getPublishUserId())
                        .title(post.getTitle())
                        .content(post.getContent())
                        .build())
                .chain(toNodes(orderedChain))
                .build();
    }

    @Override
    public BotCommentHistoryVO getHistory(Long userId, Long postId, int pageNum, int pageSize) {
        int normalizedPage = Math.max(pageNum, 1);
        int normalizedSize = Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE);
        long total = commentMapper.countBotHistory(userId, postId);
        List<ContentComment> rows = commentMapper.selectBotHistory(
                userId, postId, (normalizedPage - 1) * normalizedSize, normalizedSize);
        return BotCommentHistoryVO.builder().list(toNodes(rows)).total(total).build();
    }

    @Override
    public BotCommentTreeVO getTree(Long postId, int pageNum, int pageSize, String sortType) {
        int normalizedPage = Math.max(pageNum, 1);
        int normalizedSize = Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE);
        long total = commentMapper.countBotFloors(postId);
        List<ContentComment> rows = "desc".equals(sortType)
                ? commentMapper.selectBotFloorsDesc(
                        postId, (normalizedPage - 1) * normalizedSize, normalizedSize)
                : commentMapper.selectBotFloorsAsc(
                        postId, (normalizedPage - 1) * normalizedSize, normalizedSize);
        return BotCommentTreeVO.builder().total(total).list(toNodes(rows)).build();
    }

    private List<BotCommentNodeVO> toNodes(List<ContentComment> rows) {
        Map<Long, List<String>> imagesByCommentId = loadImages(rows);
        return rows.stream()
                .map(comment -> BotCommentNodeVO.builder()
                        .commentId(comment.getCommentId())
                        .parentId(normalizeParentId(comment.getParentId()))
                        .replyCommentId(comment.getReplyCommentId())
                        .userId(comment.getUserId())
                        .content(comment.getContent())
                        .images(imagesByCommentId.getOrDefault(comment.getCommentId(), List.of()))
                        .createTime(comment.getCreateTime() == null
                                ? ""
                                : DATE_TIME_FORMATTER.format(comment.getCreateTime()))
                        .build())
                .toList();
    }

    private Map<Long, List<String>> loadImages(List<ContentComment> rows) {
        if (rows.isEmpty()) {
            return Map.of();
        }
        List<Long> commentIds = rows.stream().map(ContentComment::getCommentId).toList();
        List<CommentImage> images = commentMapper.selectImagesByCommentIds(commentIds);
        return images.stream().collect(Collectors.groupingBy(
                CommentImage::getCommentId,
                Collectors.mapping(CommentImage::getImageUrl, Collectors.toList())));
    }

    private Long normalizeParentId(Long parentId) {
        return parentId == null || parentId == 0L ? null : parentId;
    }
}
