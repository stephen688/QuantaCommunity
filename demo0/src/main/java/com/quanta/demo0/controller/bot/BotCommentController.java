package com.quanta.demo0.controller.bot;

import com.quanta.demo0.annotation.RateLimit;
import com.quanta.demo0.controller.bot.vo.BotCommentChainVO;
import com.quanta.demo0.controller.bot.vo.BotCommentHistoryVO;
import com.quanta.demo0.controller.bot.vo.BotCommentTreeVO;
import com.quanta.demo0.result.Result;
import com.quanta.demo0.service.BotCommentService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** BOT 角色 service token 专用的评论只读接口（C-2）。 */
@RestController
@RequestMapping("/bot/comment")
@PreAuthorize("hasRole('BOT')")
@RequiredArgsConstructor
public class BotCommentController {

    private final BotCommentService botCommentService;

    @GetMapping("/chain")
    @RateLimit(scene = "bot-read", limit = 120, windowSeconds = 60, failClosed = false) // 评论链接口
    public Result<BotCommentChainVO> chain(@RequestParam Long commentId) {
        return Result.success(botCommentService.getChain(commentId));
    }

    @GetMapping("/history")
    @RateLimit(scene = "bot-read", limit = 120, windowSeconds = 60, failClosed = false)
    public Result<BotCommentHistoryVO> history(
            @RequestParam Long userId,
            @RequestParam Long postId,
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "50") Integer pageSize) {
        return Result.success(botCommentService.getHistory(userId, postId, pageNum, pageSize));
    }

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
