package com.quanta.demo0.platform.security.service.impl;

import com.quanta.demo0.platform.security.constant.RoleConstants;
import com.quanta.demo0.platform.security.dto.AdminPasswordLoginDTO;
import com.quanta.demo0.platform.security.entity.AdminCredential;
import com.quanta.demo0.platform.security.exception.AuthFailedException;
import com.quanta.demo0.platform.security.exception.RateLimitExceededException;
import com.quanta.demo0.platform.security.mapper.AdminCredentialMapper;
import com.quanta.demo0.platform.security.mapper.UserRoleMapper;
import com.quanta.demo0.platform.security.model.RateLimitDecision;
import com.quanta.demo0.platform.security.service.AdminPasswordLoginService;
import com.quanta.demo0.platform.security.service.RateLimitService;
import com.quanta.demo0.platform.security.service.SessionService;
import com.quanta.demo0.user.service.UserQueryService;
import com.quanta.demo0.user.vo.UserAccountVO;
import com.quanta.demo0.user.vo.UserLoginVO;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * 管理密码登录：双维度 Redis 原子限流 → BCrypt → 账号事实/角色 → 既有 JWT 会话。
 * 不创建用户、不授予角色；不存在/停用/错误密码统一失败。匿名入口不能使用用户级 RateLimit 切面。
 */
@Service
@RequiredArgsConstructor
public class AdminPasswordLoginServiceImpl implements AdminPasswordLoginService {
    private static final Set<String> MANAGEMENT_ROLES = Set.of(
            RoleConstants.SUPER_ADMIN, RoleConstants.OPERATIONS_ADMIN, RoleConstants.CONTENT_AUDITOR);
    // 不存在的账号仍执行同成本哈希比对，减少通过耗时探测账号的机会；此随机哈希不绑定用户。
    private final String unavailableCredentialHash = new BCryptPasswordEncoder(12).encode(UUID.randomUUID().toString());
    private final AdminCredentialMapper adminCredentialMapper;
    private final UserRoleMapper userRoleMapper;
    private final UserQueryService userQueryService;
    private final SessionService sessionService;
    private final RateLimitService rateLimitService;
    private final PasswordEncoder adminPasswordEncoder;

    /** {@inheritDoc} */
    @Override
    public UserLoginVO login(AdminPasswordLoginDTO input, String source) {
        String username = input.getUsername().trim().toLowerCase(Locale.ROOT);
        enforceLimit("admin-login-ip", source, 30);
        enforceLimit("admin-login-account", username, 5);
        String password = input.getPassword();
        if (password.getBytes(StandardCharsets.UTF_8).length > 72) throw invalidLogin();
        AdminCredential credential = adminCredentialMapper.findByUsername(username);
        String hash = credential == null ? unavailableCredentialHash : credential.getPasswordHash();
        boolean matches = adminPasswordEncoder.matches(password, hash);
        if (credential == null || !matches || !Integer.valueOf(1).equals(credential.getEnabled())) throw invalidLogin();
        UserAccountVO account = userQueryService.getAccount(credential.getUserId());
        if (account == null || Integer.valueOf(1).equals(account.getIsDeleted())
                || Integer.valueOf(1).equals(account.getAccountStatus())) throw invalidLogin();
        List<String> roles = userRoleMapper.findRoleCodesByUserId(account.getId());
        if (roles == null || roles.stream().noneMatch(MANAGEMENT_ROLES::contains)) throw invalidLogin();
        String token = sessionService.login(account);
        return UserLoginVO.builder().id(account.getId()).openid(account.getOpenid())
                .nickName(account.getNickName()).avatarUrl(account.getAvatarUrl()).token(token).build();
    }

    /** Redis 故障时拒绝密码尝试；不信任客户端可伪造的代理来源头。 */
    private void enforceLimit(String scene, String subject, int limit) {
        RateLimitDecision decision = rateLimitService.check(scene, subject, limit, 60, true);
        if (!decision.isAllowed()) throw new RateLimitExceededException("登录尝试过于频繁，请稍后重试", decision.getRetryAfterSeconds());
    }

    /** 各类凭据/账号拒绝保持相同外部提示，防止泄露账号存在和状态。 */
    private AuthFailedException invalidLogin() {
        return new AuthFailedException("账号或密码错误，或账号不可用于管理端登录");
    }
}
