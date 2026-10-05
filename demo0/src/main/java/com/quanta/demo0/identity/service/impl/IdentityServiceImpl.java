package com.quanta.demo0.identity.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.quanta.demo0.identity.dto.UserAuthDTO;
import com.quanta.demo0.identity.entity.UserAuth;
import com.quanta.demo0.identity.enums.UserAuthDisplayStatus;
import com.quanta.demo0.identity.mapper.IdentityMapper;
import com.quanta.demo0.identity.service.IdentityService;
import com.quanta.demo0.identity.service.IdentityQueryService;
import com.quanta.demo0.identity.vo.UserAuthStatusVO;
import com.quanta.demo0.platform.common.enums.AuditStatus;
import com.quanta.demo0.platform.security.context.BaseContext;
import com.quanta.demo0.platform.security.exception.AuthFailedException;
import com.quanta.demo0.user.service.UserAccountService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 用户身份认证服务实现。
 *
 * 这里只处理用户侧申请和状态/详情读取；管理员审核仍由 IdentityExamServiceImpl 承担，
 * 避免用户接口与管理端分页、审计和通知编排重新合并成一个大服务。
 */

/**
 * （补充）addUserAuth 集中了整个认证域的状态机规则，一图流：
 *
 * <pre>
 * 无记录   ──提交──> 待审核(0)                    insertUserAuth + 展示态 PENDING(1)
 * 待审核(0) ──再提交──> 拒绝                        防重复申请
 * 已通过(1) ──再提交──> 拒绝                        "用户已经认证过了"
 * 已驳回(2) ──重提──> 覆盖五项资料，回到待审核(0)      驳回后的唯一出路
 * </pre>
 *
 * 【坑 1】重提分支把 auditRemark/auditTime 置 null 想清掉旧驳回原因，但
 * updateUserAuth 的动态 SQL 会跳过 null 字段（IdentityMapper.xml），
 * 数据库里旧的驳回文案实际还留着——只是返回给前端的实体里看不见。
 * 【坑 2】方法上没有 @Transactional：插入 tb_user_auth 和回写 tb_user.auth_status
 * 是两条独立 SQL、各自提交（对比 IdentityExamServiceImpl#audit 的事务编排）。
 */
@Service
@RequiredArgsConstructor
public class IdentityServiceImpl implements IdentityService {

    private final IdentityMapper identityMapper;
    private final IdentityQueryService identityQueryService;
    private final UserAccountService userAccountService;

    /**
     * 新建认证申请，或允许被驳回的用户重新提交。
     */
    @Override
    public UserAuth addUserAuth(UserAuthDTO userAuthDTO) {
        if (userAuthDTO == null) {
            throw new AuthFailedException("认证参数不能为空");
        }
        if (userAuthDTO.getIdentityType() == null
                || userAuthDTO.getRealName() == null || userAuthDTO.getRealName().trim().isEmpty()
                || userAuthDTO.getSchoolId() == null || userAuthDTO.getSchoolId().trim().isEmpty()
                || userAuthDTO.getQuantaBatch() == null || userAuthDTO.getQuantaBatch().trim().isEmpty()
                || userAuthDTO.getQuantaDepartment() == null
                || userAuthDTO.getQuantaDepartment().trim().isEmpty()) {
            throw new AuthFailedException("认证信息不完整");
        }

        Long userId = BaseContext.getCurrentId();
        // 【安全边界】userId 从登录态取（OptionalJwtAuthenticationFilter 校验 token 后写入
        // ThreadLocal），DTO 里没有 userId 字段——用户永远只能给自己提交认证
        UserAuth userAuth = identityMapper.getUserAuthByUserId(userId);
        if (userAuth == null) {
            userAuth = BeanUtil.copyProperties(userAuthDTO, UserAuth.class);
            userAuth.setUserId(userId);
            userAuth.setAuditStatus(AuditStatus.PENDING.getCode());
            userAuth.setCreateTime(LocalDateTime.now());
            userAuth.setUpdateTime(LocalDateTime.now());
            identityMapper.insertUserAuth(userAuth);
            syncUserAuthDisplayStatus(userId, UserAuthDisplayStatus.PENDING);
            return userAuth;
        }

        if (Objects.equals(userAuth.getAuditStatus(), AuditStatus.REJECTED.getCode())) {
            // 重提：只覆盖业务五项资料，状态重置回待审核（审核记录不删，历史可追溯）
            userAuth.setIdentityType(userAuthDTO.getIdentityType());
            userAuth.setRealName(userAuthDTO.getRealName());
            userAuth.setSchoolId(userAuthDTO.getSchoolId());
            userAuth.setQuantaBatch(userAuthDTO.getQuantaBatch());
            userAuth.setQuantaDepartment(userAuthDTO.getQuantaDepartment());
            userAuth.setAuditStatus(AuditStatus.PENDING.getCode());
            // 【坑】这里置 null 想清掉旧驳回原因，但 updateUserAuth 的 <if> 跳过 null 字段，
            // 库里旧的 audit_remark / audit_time 实际不会被清掉
            userAuth.setAuditRemark(null);
            userAuth.setAuditTime(null);
            userAuth.setUpdateTime(LocalDateTime.now());
            identityMapper.updateUserAuth(userAuth);
            syncUserAuthDisplayStatus(userId, UserAuthDisplayStatus.PENDING);
            return userAuth;
        }

        if (Objects.equals(userAuth.getAuditStatus(), AuditStatus.APPROVED.getCode())) {
            throw new AuthFailedException("用户已经认证过了");
        }
        if (Objects.equals(userAuth.getAuditStatus(), AuditStatus.PENDING.getCode())) {
            throw new AuthFailedException("用户正在审核中，不能重新认证");
        }
        throw new AuthFailedException("当前状态不能重新认证");
    }

    /**
     * 读取认证状态，未提交时返回统一的 UNSUBMITTED 状态。
     */
    // 纯委托 IdentityQueryService：状态查询的"事实口径"全项目只有一个实现，
    // 避免用户侧、安全快照、个人主页三处各自查库导致口径漂移
    @Override
    public UserAuthStatusVO getAuthStatus(Long currentId) {
        return identityQueryService.getAuthStatus(currentId);
    }

    /**
     * 只有审核通过的认证记录才允许返回详情。
     */
    // 非 APPROVED 一律抛异常——详情接口只服务"已认证"状态的前端页面
    @Override
    public UserAuth getAuthDetail(Long currentId) {
        UserAuth userAuth = identityMapper.getUserAuthByUserId(currentId);
        if (userAuth == null) {
            throw new AuthFailedException("用户认证信息不存在");
        }
        if (!Objects.equals(userAuth.getAuditStatus(), AuditStatus.APPROVED.getCode())) {
            throw new AuthFailedException("用户认证信息未通过");
        }
        return userAuth;
    }

    /**
     * 把认证"事实"的变化投影到 tb_user.auth_status（展示态）。
     *
     * 【为什么绕道 UserAccountService 而不是直接改表？】tb_user 归 user 域管，
     * identity 域不持有 User Mapper——跨域只调服务、不摸别人的表。
     * 注意入参是 {@link UserAuthDisplayStatus}（0/1/2/3），与 AuditStatus 的码值不同。
     */
    private void syncUserAuthDisplayStatus(Long userId, UserAuthDisplayStatus displayStatus) {
        if (userId == null || displayStatus == null) {
            return;
        }
        userAccountService.updateAuthStatus(userId, displayStatus.getCode());
    }
}
