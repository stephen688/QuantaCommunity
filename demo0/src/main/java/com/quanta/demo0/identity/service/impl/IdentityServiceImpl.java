package com.quanta.demo0.identity.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.quanta.demo0.identity.dto.UserAuthDTO;
import com.quanta.demo0.identity.entity.UserAuth;
import com.quanta.demo0.identity.enums.UserAuthDisplayStatus;
import com.quanta.demo0.identity.service.IdentityService;
import com.quanta.demo0.identity.vo.UserAuthStatusVO;
import com.quanta.demo0.mapper.UserMapper;
import com.quanta.demo0.platform.common.enums.AuditStatus;
import com.quanta.demo0.platform.security.context.BaseContext;
import com.quanta.demo0.platform.security.exception.AuthFailedException;
import com.quanta.demo0.user.entity.User;
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
@Service
@RequiredArgsConstructor
public class IdentityServiceImpl implements IdentityService {

    private final UserMapper userMapper;

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
        UserAuth userAuth = userMapper.getUserAuthByUserId(userId);
        if (userAuth == null) {
            userAuth = BeanUtil.copyProperties(userAuthDTO, UserAuth.class);
            userAuth.setUserId(userId);
            userAuth.setAuditStatus(AuditStatus.PENDING.getCode());
            userAuth.setCreateTime(LocalDateTime.now());
            userAuth.setUpdateTime(LocalDateTime.now());
            userMapper.insertUserAuth(userAuth);
            syncUserAuthDisplayStatus(userId, UserAuthDisplayStatus.PENDING);
            return userAuth;
        }

        if (Objects.equals(userAuth.getAuditStatus(), AuditStatus.REJECTED.getCode())) {
            userAuth.setIdentityType(userAuthDTO.getIdentityType());
            userAuth.setRealName(userAuthDTO.getRealName());
            userAuth.setSchoolId(userAuthDTO.getSchoolId());
            userAuth.setQuantaBatch(userAuthDTO.getQuantaBatch());
            userAuth.setQuantaDepartment(userAuthDTO.getQuantaDepartment());
            userAuth.setAuditStatus(AuditStatus.PENDING.getCode());
            userAuth.setAuditRemark(null);
            userAuth.setAuditTime(null);
            userAuth.setUpdateTime(LocalDateTime.now());
            userMapper.updateUserAuth(userAuth);
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
    @Override
    public UserAuthStatusVO getAuthStatus(Long currentId) {
        UserAuth userAuth = userMapper.getUserAuthByUserId(currentId);
        if (userAuth == null) {
            return UserAuthStatusVO.builder()
                    .auditStatus(AuditStatus.UNSUBMITTED.getCode())
                    .build();
        }
        if (Objects.equals(userAuth.getAuditStatus(), AuditStatus.REJECTED.getCode())) {
            return UserAuthStatusVO.builder()
                    .auditStatus(userAuth.getAuditStatus())
                    .auditRemark(userAuth.getAuditRemark())
                    .build();
        }
        return UserAuthStatusVO.builder()
                .auditStatus(userAuth.getAuditStatus())
                .build();
    }

    /**
     * 只有审核通过的认证记录才允许返回详情。
     */
    @Override
    public UserAuth getAuthDetail(Long currentId) {
        UserAuth userAuth = userMapper.getUserAuthByUserId(currentId);
        if (userAuth == null) {
            throw new AuthFailedException("用户认证信息不存在");
        }
        if (!Objects.equals(userAuth.getAuditStatus(), AuditStatus.APPROVED.getCode())) {
            throw new AuthFailedException("用户认证信息未通过");
        }
        return userAuth;
    }

    private void syncUserAuthDisplayStatus(Long userId, UserAuthDisplayStatus displayStatus) {
        if (userId == null || displayStatus == null) {
            return;
        }
        User patch = new User();
        patch.setId(userId);
        patch.setAuthStatus(displayStatus.getCode());
        patch.setUpdateTime(LocalDateTime.now());
        userMapper.updateById(patch);
    }
}
