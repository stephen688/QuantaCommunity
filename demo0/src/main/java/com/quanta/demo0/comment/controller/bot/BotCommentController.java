package com.quanta.demo0.comment.controller.bot;

import com.quanta.demo0.platform.security.annotation.RateLimit;
import com.quanta.demo0.comment.vo.BotCommentChainVO;
import com.quanta.demo0.comment.vo.BotCommentHistoryVO;
import com.quanta.demo0.comment.vo.BotCommentTreeVO;
import com.quanta.demo0.platform.common.result.Result;
import com.quanta.demo0.comment.service.BotCommentService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** BOT 角色 service token 专用的评论只读接口（C-2）。 */
//
// ============================================================
// 【这是给谁用的接口？为什么单独开一个 /bot/comment 前缀？】
// ============================================================
// 调用方是外部 QuantaBot 服务（Python）。它不带普通用户会话，而是持有一张
// service token：TokenAuthenticationServiceImpl 校验 JWT 后强制要求
// userId == quantabot.bot-user-id（application.yml 中固定 10000），并授予 BOT 角色，
// 与本类的类级 @PreAuthorize("hasRole('BOT')") 配套——**service token 只能当 bot，
// 普通用户伪造不出这个角色**。单独前缀的意义：读什么、限多快、返回什么形状，
// 都按机器消费者的需要裁剪（见各方法），不和 C 端页面接口混用。
//
// 全部方法只读：Bot 拉评论链 / 帖子楼层 / 用户历史来组织回答，写回帖走
// /comment/send 复用用户端入口（bot 评论强制机审，见 CommentCommandServiceImpl.shouldModerateComment）。
@RestController
@RequestMapping("/bot/comment")
@PreAuthorize("hasRole('BOT')")
@RequiredArgsConstructor
public class BotCommentController {

    private final BotCommentService botCommentService;

    // 三个接口共用 "bot-read" 限流场景：BOT 角色 120 次/分钟（botLimit 缺省 -1 不生效时
    // bot 与普通用户同档，这里 scene 本身只挂在 bot 前缀下，120 即 bot 专属配额）。
    // failClosed=false：Redis 故障时放行而不是拒绝——读接口宁可短暂超卖配额，
    // 也不能让限流组件故障拖垮 bot 的回答能力；写接口（如 /comment/send）则保持默认 failClosed=true。

    /**
     * 评论链口：给定触发评论，返回"主楼摘要 + 该评论的直接回复链"。
     *
     * 【链怎么走】从 commentId 沿 replyCommentId（被回复对象）向上回溯，
     * 没有被回复对象时退而沿 parentId，直到顶级评论——恰好是 QuantaBot 回帖时
     * 需要的上下文：不用整棵树，只要"这条评论是怎么被一步步回出来的"。
     * 只返回可见评论（audit_status=1 且 is_deleted=0，见 selectVisibleById），
     * 中途某层不可见即停，深度上限 50 层防脏数据成环。
     */
    @GetMapping("/chain")
    @RateLimit(scene = "bot-read", limit = 120, windowSeconds = 60, failClosed = false) // 评论链接口
    public Result<BotCommentChainVO> chain(@RequestParam Long commentId) {
        return Result.success(botCommentService.getChain(commentId));
    }

    /**
     * 评论历史口：某用户在同一帖子下的全部可见评论，按时间正序分页。
     *
     * 【坑】pageNum/pageSize 由服务端归一化（pageNum 最小 1，pageSize 夹在 1~200），
     * 不信客户端裸传；userId 也是查询条件而非身份来源——bot 的身份已在 token 里。
     */
    @GetMapping("/history")
    @RateLimit(scene = "bot-read", limit = 120, windowSeconds = 60, failClosed = false)
    public Result<BotCommentHistoryVO> history(
            @RequestParam Long userId,
            @RequestParam Long postId,
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "50") Integer pageSize) {
        return Result.success(botCommentService.getHistory(userId, postId, pageNum, pageSize));
    }

    /**
     * 评论树口：帖子下全部可见评论（一级 + 回复平铺成"楼层"）按时间分页，
     * sortType=asc/desc 控制方向，缺省 asc。
     *
     * 【为什么平铺而不是嵌套树】bot 读楼层是为了了解全帖讨论脉络，
     * 平铺 + createTime 字段足以让它自行还原结构，服务端省去递归装配。
     */
    @GetMapping("/tree")
    @RateLimit(scene = "bot-read", limit = 120, windowSeconds = 60, failClosed = false)
    public Result<BotCommentTreeVO> tree(
            @RequestParam Long postId,
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "50") Integer pageSize,
            @RequestParam(defaultValue = "asc") String sortType) {
        return Result.success(botCommentService.getTree(postId, pageNum, pageSize, sortType));
    }
}
