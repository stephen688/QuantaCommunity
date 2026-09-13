package com.quanta.demo0.service.Impl;

import com.quanta.demo0.constant.AdminAuditActionConstants;
import com.quanta.demo0.constant.RoleConstants;
import com.quanta.demo0.entity.User;
import com.quanta.demo0.exception.ContentFailedException;
import com.quanta.demo0.mapper.UserMapper;
import com.quanta.demo0.mapper.UserRoleMapper;
import com.quanta.demo0.security.AuthenticatedUser;
import com.quanta.demo0.service.AdminAuditRecorder;
import com.quanta.demo0.service.AdminRoleService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Set;

import static com.quanta.demo0.constant.RedisConstants.LOGIN_USER_KEY;

/**
 * 管理端角色管理服务实现类。
 */
@Service
@Slf4j
public class AdminRoleServiceImpl implements AdminRoleService {

    /**
     * 允许管理端操作的管理角色白名单。
     */
    private static final Set<String> MANAGED_ROLES = Set.of(
            RoleConstants.CONTENT_AUDITOR,
            RoleConstants.OPERATIONS_ADMIN,
            RoleConstants.SUPER_ADMIN
    );

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private UserRoleMapper userRoleMapper;

    @Autowired
    private AdminAuditRecorder adminAuditRecorder;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Override
    @Transactional
    public void grantRole(Long userId, String roleCode) {
        // 1. 校验用户存在
        User user = userMapper.getById(userId);
        if (user == null) {
            throw new ContentFailedException("用户不存在");
        }

        // 2. 校验角色在白名单
        validateRoleCode(roleCode);

        // 3. 查询用户当前角色（用于审计摘要）
        List<String> beforeRoles = userRoleMapper.findRoleCodesByUserId(userId);

        // 4. 授予角色
        Long operatorId = currentOperatorId();
        int rows = userRoleMapper.grantRole(userId, roleCode, operatorId);

        // 5. 成功审计（跟随业务事务）
        adminAuditRecorder.recordSuccess(
                AdminAuditActionConstants.ROLE_GRANT,
                "USER_ROLE",
                userId + ":" + roleCode,
                "beforeRoles=" + String.join(",", beforeRoles),
                "afterRoles=" + String.join(",", userRoleMapper.findRoleCodesByUserId(userId))
        );

        // 6. 事务提交后删除Redis登录态，强制旧Token立即失效
        evictLoginStateAfterCommit(userId);

        log.info("授予角色成功，userId={}, roleCode={}, rows={}", userId, roleCode, rows);
    }

    @Override
    @Transactional
    public void revokeRole(Long userId, String roleCode) {
        // 1. 校验用户存在
        User user = userMapper.getById(userId);
        if (user == null) {
            throw new ContentFailedException("用户不存在");
        }

        // 2. 校验角色在白名单
        validateRoleCode(roleCode);

        // 3. 禁止操作人撤销自己唯一的SUPER_ADMIN
        if (RoleConstants.SUPER_ADMIN.equals(roleCode)
                && userId.equals(currentOperatorId())) {
            List<String> roles = userRoleMapper.findRoleCodesByUserId(userId);
            boolean hasOnlySuperAdmin = roles.size() == 1
                    && roles.contains(RoleConstants.SUPER_ADMIN);
            if (hasOnlySuperAdmin) {
                throw new ContentFailedException("不能撤销自己唯一的超级管理员角色");
            }
        }

        // 4. 查询用户当前角色（用于审计摘要）
        List<String> beforeRoles = userRoleMapper.findRoleCodesByUserId(userId);

        // 5. 撤销角色
        int rows = userRoleMapper.revokeRole(userId, roleCode);

        // 6. 成功审计（跟随业务事务）
        adminAuditRecorder.recordSuccess(
                AdminAuditActionConstants.ROLE_REVOKE,
                "USER_ROLE",
                userId + ":" + roleCode,
                "beforeRoles=" + String.join(",", beforeRoles),
                "afterRoles=" + String.join(",", userRoleMapper.findRoleCodesByUserId(userId))
        );

        // 7. 事务提交后删除Redis登录态，强制旧Token立即失效
        evictLoginStateAfterCommit(userId);

        log.info("撤销角色成功，userId={}, roleCode={}, rows={}", userId, roleCode, rows);
    }

    /**
     * 校验角色是否在管理角色白名单中。
     */
    private void validateRoleCode(String roleCode) {
        if (roleCode == null || !MANAGED_ROLES.contains(roleCode)) {
            throw new ContentFailedException("不支持的角色：" + roleCode);
        }
    }

    /**
     * 当前操作管理员ID（从SecurityContext读取）。
     */
    private Long currentOperatorId() {
        Authentication authentication =
                SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null
                && authentication.getPrincipal() instanceof AuthenticatedUser authenticatedUser) {
            return authenticatedUser.getUserId();
        }
        return null;
    }

    /**
     * 事务提交后删除该用户Redis登录态，使旧Token立即失效。
     * Redis删除不能跟着MySQL回滚，所以放到afterCommit回调。
     */
    private void evictLoginStateAfterCommit(Long userId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        try {
                            stringRedisTemplate.delete(LOGIN_USER_KEY + userId);
                            log.info("角色变更后已撤销用户登录态，userId={}", userId);
                        } catch (Exception e) {
                            log.error("角色变更后撤销登录态失败，userId={}", userId, e);
                        }
                    }
                }
        );
    }
}
