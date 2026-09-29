package com.quanta.demo0.identity.controller.user;

import com.quanta.demo0.identity.dto.UserAuthDTO;
import com.quanta.demo0.identity.entity.UserAuth;
import com.quanta.demo0.identity.service.IdentityService;
import com.quanta.demo0.identity.vo.UserAuthStatusVO;
import com.quanta.demo0.platform.common.result.Result;
import com.quanta.demo0.platform.security.context.BaseContext;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户身份认证 HTTP 门面。
 *
 * 保留原 UserController 下的 /user/auth/* 路径，只负责请求映射和统一响应，
 * 认证申请与状态业务全部委托给 IdentityService。
 */
@Tag(name = "用户身份认证", description = "用户身份认证相关接口")
@RestController
@RequestMapping("/user")
@Slf4j
@RequiredArgsConstructor
public class IdentityController {

    private final IdentityService identityService;

    /**
     * 新增或重新提交用户认证信息。
     */
    @PostMapping("/auth/add")
    public Result<UserAuth> addUserAuth(@RequestBody UserAuthDTO userAuthDTO) {
        log.info("新增用户认证信息：{}", userAuthDTO);
        return Result.success(identityService.addUserAuth(userAuthDTO));
    }

    /**
     * 查询当前用户认证状态。
     */
    @GetMapping("/auth/status")
    public Result<UserAuthStatusVO> getAuthStatus() {
        log.info("查询用户认证状态，当前用户id：{}", BaseContext.getCurrentId());
        return Result.success(identityService.getAuthStatus(BaseContext.getCurrentId()));
    }

    /**
     * 查询当前用户已通过的认证详情。
     */
    @GetMapping("/auth/detail")
    public Result<UserAuth> getAuthDetail() {
        log.info("查询用户认证详情，当前用户id：{}", BaseContext.getCurrentId());
        return Result.success(identityService.getAuthDetail(BaseContext.getCurrentId()));
    }
}
