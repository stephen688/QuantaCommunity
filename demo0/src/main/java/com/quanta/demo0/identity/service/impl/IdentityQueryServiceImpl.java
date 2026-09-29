package com.quanta.demo0.identity.service.impl;

import com.quanta.demo0.identity.entity.UserAuth;
import com.quanta.demo0.identity.mapper.IdentityMapper;
import com.quanta.demo0.identity.service.IdentityQueryService;
import com.quanta.demo0.identity.vo.UserAuthStatusVO;
import com.quanta.demo0.platform.common.enums.AuditStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * 身份认证事实查询实现。
 *
 * 职责：只读取 identity Mapper 并返回稳定 VO；
 * 边界：不依赖 user Entity，也不触发缓存或认证写入。
 */
@Service
@RequiredArgsConstructor
public class IdentityQueryServiceImpl implements IdentityQueryService {

    private final IdentityMapper identityMapper;

    /**
     * 读取认证状态，未提交时返回统一的 UNSUBMITTED 状态。
     *
     * @param userId 用户 ID
     * @return 认证状态
     */
    @Override
    public UserAuthStatusVO getAuthStatus(Long userId) {
        UserAuth userAuth = identityMapper.getUserAuthByUserId(userId);
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
}
