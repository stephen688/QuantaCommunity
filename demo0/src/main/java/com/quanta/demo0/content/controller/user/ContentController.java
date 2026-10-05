package com.quanta.demo0.content.controller.user;

import com.quanta.demo0.platform.security.annotation.RateLimit;
import com.quanta.demo0.platform.security.constant.RoleConstants;
import com.quanta.demo0.platform.common.result.Result;
import com.quanta.demo0.platform.common.result.ScrollResult;
import com.quanta.demo0.content.service.ContentQueryService;
import com.quanta.demo0.content.service.ContentCommandService;
import com.quanta.demo0.feed.service.FeedQueryService;
import com.quanta.demo0.interaction.service.ContentInteractionService;
import com.quanta.demo0.interaction.service.ReportGovernanceService;
import com.quanta.demo0.interaction.vo.CollectResultVO;
import com.quanta.demo0.content.vo.ContentVO;
import com.quanta.demo0.interaction.vo.LikeResultVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import com.quanta.demo0.platform.web.idempotency.enums.SubmissionScene;
import com.quanta.demo0.platform.web.idempotency.service.SubmissionService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import com.quanta.demo0.content.dto.ContentDTO;
import com.quanta.demo0.feed.dto.RecommendQueryDTO;
import com.quanta.demo0.interaction.dto.CollectStateDTO;
import com.quanta.demo0.interaction.dto.ContentReportDTO;
import com.quanta.demo0.interaction.dto.LikeStateDTO;

/**
 * 内容接口
 */
@RestController
@Slf4j
@RequestMapping("/content")
public class ContentController {

    @Autowired
    private ContentQueryService contentQueryService;
    @Autowired
    private ContentCommandService contentCommandService;
    @Autowired
    private FeedQueryService feedQueryService;
    @Autowired
    private ContentInteractionService contentInteractionService;
    @Autowired
    private ReportGovernanceService reportGovernanceService;
    /**
     * 发布内容
     */

    @RateLimit(
            scene = "content-publish",
            limit = 5,
            windowSeconds = 60
    )
    @PreAuthorize("hasRole('" + RoleConstants.VERIFIED_USER + "')")
    @PostMapping("/publish")
    public Result<ContentVO> publish(
            @RequestBody ContentDTO contentDTO,
            @RequestHeader(value = "Idempotency-Key", required = false) String submissionToken) {
        log.info("发布内容：{}", contentDTO);
        ContentVO contentVO = submissionService.execute(
                SubmissionScene.CONTENT_PUBLISH,
                submissionToken,
                contentDTO,
                ContentVO.class,
                () -> contentCommandService.publish(contentDTO));
        return Result.success(contentVO);
    }
    /**
     * 滑动分页查询推荐内容
     */

    @GetMapping("/recommend")
    public Result<ScrollResult> recommend(@ModelAttribute RecommendQueryDTO recommendQueryDTO){
        log.info("查询推荐内容：{}" , recommendQueryDTO);
        ScrollResult scrollResult = feedQueryService.recommend(recommendQueryDTO);
        return Result.success(scrollResult);
    }

    @Autowired
    private SubmissionService submissionService;
    /**
     * 根据contentId查询内容详情
     */

    @PreAuthorize("isAuthenticated()")
    @GetMapping("/detail/{contentId}")
    public Result<ContentVO> getContentDetail(@PathVariable Long contentId) {
        log.info("查询内容详情：{}", contentId);
        ContentVO contentVO = contentQueryService.getContentDetail(contentId);
        return Result.success(contentVO);
    }
    /**
     * 删除内容
     */
    @PreAuthorize("isAuthenticated()")
    @DeleteMapping("/delete/{contentId}")
    public Result<Void> deleteContent(@PathVariable Long contentId) {
        log.info("删除内容：{}", contentId);
        contentCommandService.deleteContent(contentId);
        return Result.success();
    }


    /**
     * 根据contentId点赞内容
     */

    @PreAuthorize("isAuthenticated()")
    @PostMapping("/like/{contentId}")
    public Result<LikeResultVO> likeContent(@PathVariable Long contentId,
                                            @Valid @RequestBody LikeStateDTO stateDTO) {
        log.info("点赞内容：{}，点赞状态：{}", contentId, stateDTO.getLiked());
        LikeResultVO likeResultVO = contentInteractionService.likeContent(contentId, stateDTO.getLiked());
        return Result.success(likeResultVO);
    }
    /**
     * 收藏或取消收藏内容
     */
    @PreAuthorize("isAuthenticated()")
    @PostMapping("/collect/{contentId}")
    public Result<CollectResultVO> collect(@PathVariable Long contentId,
                                            @Valid @RequestBody CollectStateDTO stateDTO) {
        log.info(" 收藏/取消收藏内容,内容ID:{}，收藏状态:{}", contentId, stateDTO.getCollected());
        CollectResultVO collectResultVO = contentInteractionService.collect(contentId, stateDTO.getCollected());
        return Result.success(collectResultVO);
    }
    /**
     * 举报帖子
     * 【规则】
     * - 同一用户对同一帖子只能举报一次
     * - 举报类型：1-垃圾广告 2-人身攻击 3-违规内容 4-虚假信息 5-其他
     *
     * @param contentReportDTO 举报数据
     * @return 成功
     */
    @RateLimit(
            scene = "content-report",
            limit = 5,
            windowSeconds = 60
    )
    @PreAuthorize("isAuthenticated()")
    @PostMapping("/report")
    public Result reportContent(@RequestBody ContentReportDTO contentReportDTO) {
        log.info("举报帖子: {}", contentReportDTO);
        reportGovernanceService.reportContent(contentReportDTO);
        return Result.success();
    }


}
