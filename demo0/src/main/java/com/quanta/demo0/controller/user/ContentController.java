package com.quanta.demo0.controller.user;

import com.quanta.demo0.annotation.RateLimit;
import com.quanta.demo0.constant.RoleConstants;
import com.quanta.demo0.dto.*;
import com.quanta.demo0.result.Result;
import com.quanta.demo0.result.ScrollResult;
import com.quanta.demo0.service.ContentService;
import com.quanta.demo0.vo.CollectResultVO;
import com.quanta.demo0.vo.ContentVO;
import com.quanta.demo0.vo.LikeResultVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;

/**
 * 内容接口
 */
@RestController
@Slf4j
@RequestMapping("/content")
public class ContentController {

    @Autowired
    private ContentService contentService;
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
    public Result<ContentVO> publish(@RequestBody ContentDTO contentDTO) {
        log.info("发布内容：{}", contentDTO);
        ContentVO contentVO = contentService.publish(contentDTO);
        return Result.success(contentVO);
    }
    /**
     * 滑动分页查询推荐内容
     */

    @GetMapping("/recommend")
    public Result<ScrollResult> recommend(@ModelAttribute RecommendQueryDTO recommendQueryDTO){
        log.info("查询推荐内容：{}" , recommendQueryDTO);
        ScrollResult scrollResult = contentService.recommend(recommendQueryDTO);
        return Result.success(scrollResult);
    }

    /**
     * 根据contentId查询内容详情
     */

    @PreAuthorize("isAuthenticated()")
    @GetMapping("/detail/{contentId}")
    public Result<ContentVO> getContentDetail(@PathVariable Long contentId) {
        log.info("查询内容详情：{}", contentId);
        ContentVO contentVO = contentService.getContentDetail(contentId);
        return Result.success(contentVO);
    }
    /**
     * 删除内容
     */
    @PreAuthorize("isAuthenticated()")
    @DeleteMapping("/delete/{contentId}")
    public Result<Void> deleteContent(@PathVariable Long contentId) {
        log.info("删除内容：{}", contentId);
        contentService.deleteContent(contentId);
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
        LikeResultVO likeResultVO = contentService.likeContent(contentId, stateDTO.getLiked());
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
        CollectResultVO collectResultVO = contentService.collect(contentId, stateDTO.getCollected());
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
        contentService.reportContent(contentReportDTO);
        return Result.success();
    }


}
