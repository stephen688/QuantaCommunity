package com.quanta.demo0.platform.security.controller.admin;

import com.quanta.demo0.platform.common.result.Result;
import com.quanta.demo0.platform.security.dto.AdminPasswordLoginDTO;
import com.quanta.demo0.platform.security.exception.AuthFailedException;
import com.quanta.demo0.platform.security.service.AdminPasswordLoginService;
import com.quanta.demo0.platform.security.service.SessionService;
import com.quanta.demo0.platform.security.properties.JwtProperties;
import com.quanta.demo0.platform.security.model.AuthenticatedUser;
import org.springframework.security.core.Authentication;
import org.springframework.security.access.prepost.PreAuthorize;
import com.quanta.demo0.user.vo.UserLoginVO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** 管理端身份入口：密码登录精确匿名放行，退出须管理角色；凭据与会话由安全 Service 处理。 */
@RestController
@RequestMapping("/admin/auth")
@RequiredArgsConstructor
public class AdminPasswordLoginController {
    private final AdminPasswordLoginService adminPasswordLoginService;
    private final SessionService sessionService;
    private final JwtProperties jwtProperties;

    /** 真实管理员退出：仅撤销本请求对应的会话，基础设施失败不返回成功。 */
    @PostMapping("/logout")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'OPERATIONS_ADMIN', 'CONTENT_AUDITOR')")
    public Result<Void> logout(Authentication authentication, HttpServletRequest request) {
        AuthenticatedUser user = (AuthenticatedUser) authentication.getPrincipal();
        String token = request.getHeader(jwtProperties.getUserTokenName());
        if (token != null && token.regionMatches(true, 0, "Bearer ", 0, 7)) token = token.substring(7).trim();
        sessionService.revokeSession(user.getUserId(), token);
        return Result.success();
    }

    /** 返回既有登录 VO；凭据拒绝保持 HTTP/body 401，不改变旧微信登录异常契约。 */
    @PostMapping("/login")
    public ResponseEntity<Result<UserLoginVO>> login(@Valid @RequestBody AdminPasswordLoginDTO input,
                                                    HttpServletRequest request) {
        try {
            return ResponseEntity.ok(Result.success(adminPasswordLoginService.login(input, request.getRemoteAddr())));
        } catch (AuthFailedException exception) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Result.error(401, exception.getMessage()));
        }
    }
}
