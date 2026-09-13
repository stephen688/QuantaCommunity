package com.quanta.demo0.controller.user;

import com.quanta.demo0.annotation.RateLimit;
import com.quanta.demo0.constant.RoleConstants;
import com.quanta.demo0.dto.CommentAddDTO;
import com.quanta.demo0.dto.CommentPageDTO;
import com.quanta.demo0.dto.CommentReportDTO;
import com.quanta.demo0.dto.LikeStateDTO;
import com.quanta.demo0.dto.ReplyPageDTO;
import com.quanta.demo0.result.Result;
import com.quanta.demo0.service.CommentService;
import com.quanta.demo0.vo.CommentPageVO;
import com.quanta.demo0.vo.LikeResultVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/comment")
@Slf4j
public class CommentController {

    @Autowired
    private CommentService commentService;

    /**
     * 发布评论
     */

    @RateLimit(
            scene = "comment-send",
            limit = 10,
            windowSeconds = 60
    )
    @PreAuthorize("hasRole('" + RoleConstants.VERIFIED_USER + "')")
    @PostMapping("/send")
    public Result sendComment(@RequestBody CommentAddDTO commentAddDTO) {
        log.info("发布评论: {}", commentAddDTO);
        Long commentId = commentService.sendComment(commentAddDTO);
        return Result.success(commentId);


    }

    /**
     * 查询一级评论列表
     */
    @PreAuthorize("isAuthenticated()")
    @GetMapping("/list")
    public Result<CommentPageVO> commentList(@ModelAttribute("commentPageDTO") CommentPageDTO commentPageDTO) {
        log.info("查询评论列表: {}", commentPageDTO);
        CommentPageVO commentPageVO = commentService.commentPage(commentPageDTO);
        return Result.success(commentPageVO);
    }


    /**
     * 查询二级评论列表
     */
    @PreAuthorize("isAuthenticated()")
    @GetMapping("/replyList")
    public Result<CommentPageVO> replyList(@ModelAttribute("replyPageDTO") ReplyPageDTO replyPageDTO) {
        log.info("查询回复列表: {}", replyPageDTO);
        CommentPageVO replyPageVO = commentService.replyPage(replyPageDTO);
        return Result.success(replyPageVO);
    }




    /**
     * 删除评论
     */

    @PreAuthorize("isAuthenticated()")
    @DeleteMapping("/delete/{commentId}")
    public Result deleteComment(@PathVariable Long commentId) {

        log.info("删除评论: {}", commentId);
        commentService.deleteComment(commentId);
        return Result.success();
    }


    /**
     * 设置评论点赞状态
     */
    @PreAuthorize("isAuthenticated()")
    @PostMapping("/like/{commentId}")
    public Result<LikeResultVO> likeComment(@PathVariable Long commentId,
                                            @Valid @RequestBody LikeStateDTO stateDTO) {

        log.info("点赞评论: {}，点赞状态：{}", commentId, stateDTO.getLiked());
        LikeResultVO likeResultVO = commentService.likeComment(commentId, stateDTO.getLiked());
        return Result.success(likeResultVO);
    }

    /**
     * 评论举报
     */
    @RateLimit(
            scene = "comment-report",
            limit = 5,
            windowSeconds = 60
    )
    @PreAuthorize("isAuthenticated()")
    @PostMapping("/report")
    public Result reportComment(@RequestBody CommentReportDTO commentReportDTO) {
        log.info("举报评论: {}", commentReportDTO);
        commentService.reportComment(commentReportDTO);
        return Result.success();
    }


}
