
package com.quanta.demo0.controller.user;
import com.quanta.demo0.annotation.RateLimit;
import com.quanta.demo0.constant.RoleConstants;
import com.quanta.demo0.dto.AnswerDTO;
import com.quanta.demo0.dto.LikeStateDTO;
import com.quanta.demo0.result.Result;
import com.quanta.demo0.service.AnswerService;
import com.quanta.demo0.vo.AnswerVO;
import com.quanta.demo0.vo.LikeResultVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;

import java.util.List;

/**
 * 回答接口
 *
 * @author Quanta Team
 * @since 2026-05-01
 */
@RestController
@Slf4j
@RequestMapping("/answer")
public class AnswerController {

    @Autowired
    private AnswerService answerService;

    /**
     * 发布回答
     * 【规则】
     * - 仅专业区问题可回答（contentType=2）
     * - 问题必须存在且审核通过
     * - 敏感词校验
     * @param answerDTO 回答数据
     * @return 回答 VO
     */
    @RateLimit(
            scene = "answer-publish",
            limit = 10,
            windowSeconds = 60
    )
    @PreAuthorize("hasRole('" + RoleConstants.VERIFIED_USER + "')")
    @PostMapping("/publish")
    public Result<AnswerVO> publishAnswer(@RequestBody AnswerDTO answerDTO) {
        log.info("发布回答: {}", answerDTO);
        AnswerVO answerVO = answerService.publishAnswer(answerDTO);
        return Result.success(answerVO);
    }

    /**
     * 查询问题的回答列表
     * 【排序规则】
     * - 已采纳的回答排在最前面
     * - 点赞数多的排在前面
     * - 时间新的排在前面
     * @param questionId 问题 ID
     * @return 回答列表
     */
    @GetMapping("/list/{questionId}")
    public Result<List<AnswerVO>> getAnswers(@PathVariable Long questionId) {
        log.info("查询回答列表，问题 ID: {}", questionId);
        List<AnswerVO> answerVOList = answerService.getAnswersByQuestionId(questionId);
        return Result.success(answerVOList);
    }


    /**
     * 采纳回答（仅题主可操作）
     * 【规则】
     * - 仅问题发布者（题主）可采纳
     * - 一个问题只能有一条回答被采纳
     *
     * @param answerId 回答 ID
     * @return 成功
     */
    @PreAuthorize("isAuthenticated()")
    @PostMapping("/accept/{answerId}")
    public Result acceptAnswer(@PathVariable Long answerId) {
        log.info("采纳回答: {}", answerId);
        answerService.acceptAnswer(answerId);
        return Result.success();
    }


    /**
     * 设置回答点赞状态
     */
    @PreAuthorize("isAuthenticated()")
    @PostMapping("/like/{answerId}")
    public Result<LikeResultVO> likeAnswer(@PathVariable Long answerId,
                                           @Valid @RequestBody LikeStateDTO stateDTO) {
        log.info("点赞回答: {}，点赞状态：{}", answerId, stateDTO.getLiked());
        LikeResultVO result = answerService.likeAnswer(answerId, stateDTO.getLiked());
        return Result.success(result);
    }


    /**
     * 删除回答（仅回答作者或题主可操作）
     *
     * 【规则】
     * - 仅回答作者或问题发布者（题主）可删除
     * - 软删除回答本身
     * - 物理删除关联数据
     *
     * @param answerId 回答 ID
     * @return 成功
     */
    @PreAuthorize("isAuthenticated()")
    @DeleteMapping("/{answerId}")
    public Result deleteAnswer(@PathVariable Long answerId) {
        log.info("删除回答: {}", answerId);
        answerService.deleteAnswer(answerId);
        return Result.success();
    }

    /**
     * 查询回答详情
     * 【返回内容】
     * - 回答基本信息
     * - 回答用户信息（昵称、头像、届数）
     *
     * @param answerId 回答 ID
     * @return 回答详情
     */
    @GetMapping("/{answerId}")
    public Result<AnswerVO> getAnswerDetail(@PathVariable Long answerId) {
        log.info("查询回答详情: {}", answerId);
        AnswerVO answerVO = answerService.getAnswerDetail(answerId);
        return Result.success(answerVO);
    }
}
