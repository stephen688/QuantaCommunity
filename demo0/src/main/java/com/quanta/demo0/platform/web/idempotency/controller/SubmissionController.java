package com.quanta.demo0.platform.web.idempotency.controller;

import com.quanta.demo0.platform.common.result.Result;
import com.quanta.demo0.platform.security.annotation.RateLimit;
import com.quanta.demo0.platform.web.idempotency.service.SubmissionService;
import com.quanta.demo0.platform.web.idempotency.vo.SubmissionStatusVO;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP 提交状态查询入口。
 *
 * 职责：只接收固定场景和请求头并调用公共查询 Service；边界：不执行写操作、不读取 Mapper。
 */
@RestController
@RequestMapping("/submission")
@RequiredArgsConstructor
public class SubmissionController {

    private final SubmissionService submissionService;

    /** 查询当前用户自己的提交状态，避免把完整凭证放进 URL。 */
    @RateLimit(scene = "submission-status", limit = 30, windowSeconds = 60)
    @PreAuthorize("isAuthenticated()")
    @GetMapping("/status")
    public Result<SubmissionStatusVO> status(
            @RequestParam(required = false) String scene,
            @RequestHeader(value = "Idempotency-Key", required = false) String token) {
        return Result.success(submissionService.query(scene, token));
    }
}
