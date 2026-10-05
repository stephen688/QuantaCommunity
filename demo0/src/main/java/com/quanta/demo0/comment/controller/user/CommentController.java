package com.quanta.demo0.comment.controller.user;

import com.quanta.demo0.platform.security.annotation.RateLimit;
import com.quanta.demo0.platform.security.constant.RoleConstants;
import com.quanta.demo0.comment.dto.CommentAddDTO;
import com.quanta.demo0.comment.dto.CommentPageDTO;
import com.quanta.demo0.interaction.dto.CommentReportDTO;
import com.quanta.demo0.interaction.dto.LikeStateDTO;
import com.quanta.demo0.comment.dto.ReplyPageDTO;
import com.quanta.demo0.platform.common.result.Result;
import com.quanta.demo0.comment.service.CommentCommandService;
import com.quanta.demo0.comment.service.CommentQueryService;
import com.quanta.demo0.interaction.service.CommentInteractionService;
import com.quanta.demo0.comment.vo.CommentPageVO;
import com.quanta.demo0.interaction.vo.LikeResultVO;
import com.quanta.demo0.platform.web.idempotency.enums.SubmissionScene;
import com.quanta.demo0.platform.web.idempotency.service.SubmissionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;

/**
 * 用户端评论入口控制器（C 端）。
 *
 * 承载评论的用户侧读写入口：发布 / 一级评论分页 / 二级回复分页 / 删除 / 点赞 / 举报。
 * 点赞与举报本体在 interaction 包（CommentInteractionService），这里只做跨域转发——
 * 评论域负责"评论事实"，互动域负责"用户对评论的关系"，两者刻意分离。
 *
 * ============================================================
 * 【为什么控制器这么"薄"，连身份都不从请求里拿？】
 * ============================================================
 * 整个类没有任何参数承接 userId：评论者身份统一在 Service 层从
 * BaseContext.getCurrentId() 取（见 CommentCommandServiceImpl.sendComment 第 1 步），
 * 而 BaseContext 由 JWT 过滤器解析 token 后写入。
 * **对比反例：如果 DTO 里带 userId 字段并以请求体为准，任何登录用户都能
 * 伪造别人的身份发评论 / 删评论。** 前端传什么不重要，token 是唯一身份来源。
 * 同理 /delete/{commentId} 只声明 isAuthenticated()，"本人 / 题主 / 答主"
 * 的细粒度归属校验下沉到 CommentCommandServiceImpl.deleteComment 完成。
 */
@RestController
@RequestMapping("/comment")
@Slf4j
public class CommentController {

    @Autowired
    private CommentCommandService commentCommandService;
    @Autowired
    private CommentQueryService commentQueryService;
    @Autowired
    private CommentInteractionService commentInteractionService;
    @Autowired
    private SubmissionService submissionService;

    /**
     * 发布评论
     */

    // 限流三件套：scene 维度 + 每 60 秒窗口 + BOT 独立配额。
    // botLimit=6 的含义见 RateLimit.botLimit()：BOT 角色请求时 scene 会改写为
    // "comment-send-bot"，每分钟 6 次（RateLimitAspect 中 scene + "-bot" 的拼装逻辑），
    // 与普通用户的 10 次/分钟隔离，防止 bot 回帖风暴挤占正常用户配额。
    // 角色上放行 VERIFIED_USER（实名用户）与 BOT（QuantaBot 用 service token 回帖走同一入口）。
    @RateLimit(
            scene = "comment-send",
            limit = 10,
            windowSeconds = 60,
            botLimit = 6
    )
    @PreAuthorize("hasRole('" + RoleConstants.VERIFIED_USER + "') "
            + "or hasRole('" + RoleConstants.BOT + "')")
    @PostMapping("/send")
    public Result<Long> sendComment(
            @RequestBody CommentAddDTO commentAddDTO,
            @RequestHeader(value = "Idempotency-Key", required = false) String submissionToken) {
        // 注意返回值只给新评论 ID：评论此时是"待审核"状态（见 sendComment 第 5 步），
        // 前端可先本地渲染草稿，待审核事件走完后由列表查询刷新为可见。
        log.info("发布评论: {}", commentAddDTO);
        Long commentId = submissionService.execute(
                SubmissionScene.COMMENT_SEND,
                submissionToken,
                commentAddDTO,
                Long.class,
                () -> commentCommandService.sendComment(commentAddDTO));
        return Result.success(commentId);


    }

    /**
     * 查询一级评论列表
     */
    // GET + @ModelAttribute：分页参数从 query string 绑定到 DTO，而非请求体。
    // 只返回一级评论（parent_id IS NULL 且 audit_status=1），每条附前 3 条回复内联预览
    // 与回复总数，"展开全部回复"由前端再调 /replyList 二次分页——经典的两段式评论加载。
    @PreAuthorize("isAuthenticated()")
    @GetMapping("/list")
    public Result<CommentPageVO> commentList(@ModelAttribute("commentPageDTO") CommentPageDTO commentPageDTO) {
        log.info("查询评论列表: {}", commentPageDTO);
        CommentPageVO commentPageVO = commentQueryService.commentPage(commentPageDTO);
        return Result.success(commentPageVO);
    }


    /**
     * 查询二级评论列表
     */
    // 与 /list 的排序语义不同：这里 sortType=1 时间正序 / 2 时间倒序（见 ReplyPageDTO），
    // 而 /list 的 sortType=2 是点赞倒序——两个接口的"2"含义不一致，前端须分别对待。
    @PreAuthorize("isAuthenticated()")
    @GetMapping("/replyList")
    public Result<CommentPageVO> replyList(@ModelAttribute("replyPageDTO") ReplyPageDTO replyPageDTO) {
        log.info("查询回复列表: {}", replyPageDTO);
        CommentPageVO replyPageVO = commentQueryService.replyPage(replyPageDTO);
        return Result.success(replyPageVO);
    }




    /**
     * 删除评论
     */

    // 谁能删不由这里判断：Service 层校验"评论本人 / 题主 / 答主"三种身份，
    // 不满足抛 CommentFailedException（全局处理器转 400）。
    // 删除是软删（is_deleted=1）并级联回复，图片与点赞明细则物理删除，
    // 且删除动作与热度 / 搜索 Outbox 同事务提交，保证下游最终一致。
    @PreAuthorize("isAuthenticated()")
    @DeleteMapping("/delete/{commentId}")
    public Result deleteComment(@PathVariable Long commentId) {

        log.info("删除评论: {}", commentId);
        commentCommandService.deleteComment(commentId);
        return Result.success();
    }


    /**
     * 设置评论点赞状态
     */
    // 转发 interaction 包 CommentInteractionService.likeComment：点赞关系（谁赞了谁）
    // 属于互动域，评论域只持有聚合的 like_count 计数。
    @PreAuthorize("isAuthenticated()")
    @PostMapping("/like/{commentId}")
    public Result<LikeResultVO> likeComment(@PathVariable Long commentId,
                                            @Valid @RequestBody LikeStateDTO stateDTO) {

        log.info("点赞评论: {}，点赞状态：{}", commentId, stateDTO.getLiked());
        LikeResultVO likeResultVO = commentInteractionService.likeComment(commentId, stateDTO.getLiked());
        return Result.success(likeResultVO);
    }

    /**
     * 评论举报
     */
    // 举报比发言限得更狠：5 次/分钟且无 bot 独立配额（botLimit 缺省 -1 不生效）。
    // 举报单的查询与处置在管理端 AdminCommentController，形成 C 端上报 → B 端治理的闭环。
    @RateLimit(
            scene = "comment-report",
            limit = 5,
            windowSeconds = 60
    )
    @PreAuthorize("isAuthenticated()")
    @PostMapping("/report")
    public Result reportComment(@RequestBody CommentReportDTO commentReportDTO) {
        log.info("举报评论: {}", commentReportDTO);
        commentInteractionService.reportComment(commentReportDTO);
        return Result.success();
    }


}
