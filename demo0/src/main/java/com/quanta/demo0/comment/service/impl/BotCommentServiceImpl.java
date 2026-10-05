package com.quanta.demo0.comment.service.impl;

import com.quanta.demo0.comment.vo.BotCommentChainVO;
import com.quanta.demo0.comment.vo.BotCommentHistoryVO;
import com.quanta.demo0.comment.vo.BotCommentNodeVO;
import com.quanta.demo0.comment.vo.BotCommentTreeVO;
import com.quanta.demo0.content.vo.BotPostVO;
import com.quanta.demo0.comment.entity.CommentImage;
import com.quanta.demo0.comment.entity.ContentComment;
import com.quanta.demo0.comment.exception.CommentFailedException;
import com.quanta.demo0.comment.mapper.CommentMapper;
import com.quanta.demo0.content.service.ContentQueryService;
import com.quanta.demo0.content.vo.ContentSnapshotVO;
import com.quanta.demo0.comment.service.BotCommentService;
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
 *
 * ============================================================
 * 【为什么 bot 不复用用户侧的 commentPage？】
 * ============================================================
 * 三处口径差异，强行复用会互相拖累：
 * 1) 可见性与数据面：这里只走"可见口径"SQL（selectVisibleById 等，audit_status = 1
 *    且 is_deleted = 0，见 CommentMapper 的 bot 系列语句），且不查昵称、点赞等展示数据——
 *    BotCommentNodeVO 只吐原始事实（文本/图片/时间/用户 id），**怎么组织语言是 bot 的事，
 *    怎么展示是前端的事**；
 * 2) 分页方式：不用 PageHelper，改为 limit #{offset}, #{limit} 手工分页 + 独立 count 语句，
 *    页大小钳制在 MAX_PAGE_SIZE = 200；
 * 3) 输出形态：链 / 用户历史 / 楼层都是线性时间流，正好作为 LLM 上下文，
 *    不需要楼中楼的交互结构。入口在 BotCommentController（/bot/comment/**，BOT 角色鉴权 + 限流）。
 */
@Service
@RequiredArgsConstructor
public class BotCommentServiceImpl implements BotCommentService {

    private static final DateTimeFormatter DATE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int MAX_CHAIN_DEPTH = 50;
    private static final int MAX_PAGE_SIZE = 200;

    private final CommentMapper commentMapper;
    private final ContentQueryService contentQueryService;

    /**
     * 从触发评论（通常是 @到 bot 的那条）沿祖先链向上回溯，输出"最早祖先 -> 触发评论"的时间正序链。
     *
     * <p>【锚点选择】每层先看 replyCommentId（"我在回复谁"），没有再看 parentId（"我挂在哪一楼"），
     * 两者都空或为 0 即到达根。</p>
     * <p>【防环 + 兜底】visitedCommentIds.add() 返回 false 说明这条链回指了已访问节点，立即停；
     * MAX_CHAIN_DEPTH = 50 兜底超长链，防止脏数据把 bot 拖进深循环。</p>
     * <p>【为什么用栈】ArrayDeque.push 是头插：从触发评论逐层向祖先 push，
     * 收尾转 List 的迭代顺序恰好就是"祖先在前、触发评论在后"的时间正序，免一次 reverse。</p>
     */
    // 评论链口，获取评论链（包含所有回复）
    @Override
    public BotCommentChainVO getChain(Long commentId) {
        ContentComment trigger = commentMapper.selectVisibleById(commentId);//
        if (trigger == null) {
            throw new CommentFailedException("评论不存在或不可见");
        }
        ContentSnapshotVO post = contentQueryService.getContentSnapshot(trigger.getContentId());
        if (post == null) {
            throw new CommentFailedException("帖子不存在或已删除");
        }

        Deque<ContentComment> chainStack = new ArrayDeque<>();// 评论链栈
        Set<Long> visitedCommentIds = new HashSet<>();// 已访问评论ID集合
        ContentComment current = trigger;// 当前评论
        // 递归遍历评论链，直到到达根评论或最大深度
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

        // 评论链栈中的评论按时间顺序排序
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

    /**
     * 查询某用户在某帖子下的全部可见评论，时间正序分页——即 bot 视角的"这个人在该帖子下说过什么"。
     *
     * <p>【参数钳制】页码最小 1，页大小钳在 [1, 200]；total 用同口径 count 单独查
     * （countBotHistory），列表与总数两条 SQL，不为拿总数多拉数据。</p>
     */
    // 评论历史口，获取评论历史（包含所有回复）
    @Override
    public BotCommentHistoryVO getHistory(Long userId, Long postId, int pageNum, int pageSize) {
        int normalizedPage = Math.max(pageNum, 1);//
        int normalizedSize = Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE);// 分页大小
        long total = commentMapper.countBotHistory(userId, postId);// 总评论数
        List<ContentComment> rows = commentMapper.selectBotHistory(
                userId, postId, (normalizedPage - 1) * normalizedSize, normalizedSize);
        return BotCommentHistoryVO.builder().list(toNodes(rows)).total(total).build();
    }

    /**
     * 查询帖子下全部可见评论（一楼 + 回复混排的"楼层流"），按时间正/倒序分页。
     *
     * <p>【与用户侧差异】不区分层级、不组装树——bot 只需要按时间线读完整上下文；
     * sortType 字符串为 "desc" 走倒序，其余任何值（含默认 asc）都走正序。</p>
     */
    // 评论树口，获取评论树（包含所有回复）
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

    // 组装 bot 节点：createTime 为空时输出空串，保证 VO 字段形态稳定
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

    // 一次 IN 查询把本页所有评论的图片查回并按 commentId 分组——防每条评论查一次图片表的 N+1
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

    // 根评论的 parentId 统一输出 null（0 也视为根），bot 侧只需判断"有没有父节点"
    private Long normalizeParentId(Long parentId) {
        return parentId == null || parentId == 0L ? null : parentId;
    }
}
