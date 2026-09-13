package com.quanta.demo0.service.Impl;


import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.quanta.demo0.constant.AdminAuditActionConstants;
import com.quanta.demo0.dto.IdentityAuditDTO;
import com.quanta.demo0.dto.IdentityExamDTO;
import com.quanta.demo0.entity.User;
import com.quanta.demo0.entity.UserAuth;
import com.quanta.demo0.enums.AuditStatus;
import com.quanta.demo0.enums.NotificationType;
import com.quanta.demo0.enums.UserAuthDisplayStatus;
import com.quanta.demo0.exception.AuthFailedException;
import com.quanta.demo0.mapper.IdentityExamMapper;
import com.quanta.demo0.mapper.UserMapper;
import com.quanta.demo0.mq.message.NotificationEventMessage;
import com.quanta.demo0.result.PageResult;
import com.quanta.demo0.security.VerifiedStatusCacheEvictor;
import com.quanta.demo0.service.AdminAuditRecorder;
import com.quanta.demo0.service.IdentityExamService;
import com.quanta.demo0.service.OutboxEventService;
import com.quanta.demo0.vo.IdentityDetailVO;
import com.quanta.demo0.vo.IdentityExamVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 身份认证审核服务实现类。
 *
 * 核心职责：
 * 1. 提供认证申请分页查询与详情查询，支撑管理端审核流程；
 * 2. 执行身份审核通过/驳回，更新用户认证状态与展示状态；
 * 3. 在审核完成后发送结果通知，闭环认证申请生命周期。
 *
 * 设计说明：
 * - 审核操作使用事务，保证认证表与用户表状态同步更新；
 * - 统一状态文案映射，减少前端重复状态解释逻辑。
 */
@Service
@Slf4j
public class IdentityExamServiceImpl implements IdentityExamService {
    private static final String USER_AUTH_AGGREGATE_TYPE = "USER_AUTH";

    @Autowired
    private IdentityExamMapper identityExamMapper;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private OutboxEventService outboxEventService;

    /**
     * 用户认证状态缓存失效器。
     */
    @Autowired
    private VerifiedStatusCacheEvictor verifiedStatusCacheEvictor;

    /**
     * 管理员审计记录器。
     */
    @Autowired
    private AdminAuditRecorder adminAuditRecorder;


    @Override
    public PageResult pageQuery(IdentityExamDTO identityExamDTO) {
        int pageNum = identityExamDTO.getPageNum() != null ? identityExamDTO.getPageNum() : 1;
        int pageSize = identityExamDTO.getPageSize() != null ? identityExamDTO.getPageSize() : 10;

        PageHelper.startPage(pageNum, pageSize);

        Page<IdentityExamVO> page = identityExamMapper.list(identityExamDTO);
// 统一在 Java 层根据枚举填充状态文案
        page.getResult().forEach(item -> {
            item.setStatusText(AuditStatus.descByCode(item.getAuditStatus()));
        });

        return new PageResult(page.getTotal(), page.getResult());

    }


    @Transactional
    @Override
    public IdentityDetailVO getDetailById(Long authId) {
        if (authId == null) {

            throw new AuthFailedException("authId不能为空");
        }
        UserAuth auth = userMapper.getUserAuthByAuthId(authId);
        if (auth == null) {
            throw new AuthFailedException("用户身份认证信息不存在");
        }
        Long userId = auth.getUserId();
        User user = userMapper.getById(userId);
        IdentityDetailVO identityDetailVO = IdentityDetailVO.builder()
                .authId(auth.getAuthId())
                .userId(userId)
                .nickName(user.getNickName())
                .realName(auth.getRealName())
                .avatarUrl(user.getAvatarUrl())
                .quantaBatch(auth.getQuantaBatch())
                .schoolId(auth.getSchoolId())
                .createTime(auth.getCreateTime())
                .updateTime(auth.getUpdateTime())
                .quantaDepartment(auth.getQuantaDepartment())
                .auditStatus(auth.getAuditStatus())
                .auditRemark(auth.getAuditRemark())
                .build();

        return identityDetailVO;

    }


    @Transactional
    @Override
    public void audit(IdentityAuditDTO identityAuditDTO) {

        if (identityAuditDTO.getAuthId() == null) {
            throw new AuthFailedException("authId不能为空");
        }
        UserAuth auth = userMapper.getUserAuthByAuthId(identityAuditDTO.getAuthId());
        if (auth == null) {
            throw new AuthFailedException("用户身份认证信息不存在");
        }
        User user=new User();
        //认证成功
        if (identityAuditDTO.getAuditResult() == AuditStatus.APPROVED.getCode()) {
            user.setId(auth.getUserId());
            auth.setAuditTime(LocalDateTime.now());
            auth.setAuditStatus(AuditStatus.APPROVED.getCode());
            userMapper.updateUserAuth(auth);
            user.setAuthStatus(UserAuthDisplayStatus.VERIFIED.getCode());
            userMapper.updateById(user);
        } else if (identityAuditDTO.getAuditResult() == AuditStatus.REJECTED.getCode()) {
            //认证驳回
            auth.setAuditStatus(AuditStatus.REJECTED.getCode());
            auth.setAuditRemark(identityAuditDTO.getAuditRemark());
            auth.setAuditTime(LocalDateTime.now());
            userMapper.updateUserAuth(auth);
            user.setId(auth.getUserId());
            user.setAuthStatus(UserAuthDisplayStatus.REJECTED.getCode());
            userMapper.updateById(user);

        } else {
            throw new AuthFailedException("审核状态只能为" + AuditStatus.APPROVED.getCode() + " 或者 " + AuditStatus.REJECTED.getCode() + " 之间的");
        }
        String notifyContent = identityAuditDTO.getAuditResult() == AuditStatus.APPROVED.getCode()
                ? "你的身份认证已通过"
                : "你的身份认证未通过，原因：" + (identityAuditDTO.getAuditRemark() != null ? identityAuditDTO.getAuditRemark() : "");

        NotificationEventMessage identityNotification = NotificationEventMessage.builder()
                .recipientUserId(auth.getUserId())
                .actorUserId(null)
                .type(NotificationType.IDENTITY_AUDIT_RESULT.getCode())
                .content(notifyContent)
                .payload(Map.of(
                        "authId", identityAuditDTO.getAuthId(),
                        "auditResult", identityAuditDTO.getAuditResult(),
                        "auditRemark", identityAuditDTO.getAuditRemark() != null ? identityAuditDTO.getAuditRemark() : ""
                ))
                .build();

        // 认证表、用户展示状态和通知 Outbox 在同一个事务中提交。
        outboxEventService.createNotificationEvent(identityNotification, USER_AUTH_AGGREGATE_TYPE, identityAuditDTO.getAuthId());

        /*
         * 认证通过或驳回后，使旧的认证状态缓存失效。
         *
         * 此处只是登记事务提交回调：
         * 数据库事务成功提交后才会真正删除Redis缓存。
         */
        verifiedStatusCacheEvictor.evictAfterCommit(auth.getUserId());

        // 审计：身份审核成功
        adminAuditRecorder.recordSuccess(
                AdminAuditActionConstants.IDENTITY_AUDIT,
                "IDENTITY_AUTH",
                String.valueOf(identityAuditDTO.getAuthId()),
                "auditStatus=PENDING",
                "auditStatus=" + identityAuditDTO.getAuditResult()
        );

    }
}
