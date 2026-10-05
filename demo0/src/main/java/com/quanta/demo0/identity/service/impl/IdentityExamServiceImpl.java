package com.quanta.demo0.identity.service.impl;


import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.quanta.demo0.platform.audit.constant.AdminAuditActionConstants;
import com.quanta.demo0.identity.dto.IdentityAuditDTO;
import com.quanta.demo0.identity.dto.IdentityExamDTO;
import com.quanta.demo0.identity.entity.UserAuth;
import com.quanta.demo0.platform.common.enums.AuditStatus;
import com.quanta.demo0.notification.enums.NotificationType;
import com.quanta.demo0.identity.enums.UserAuthDisplayStatus;
import com.quanta.demo0.platform.security.exception.AuthFailedException;
import com.quanta.demo0.identity.mapper.IdentityExamMapper;
import com.quanta.demo0.identity.mapper.IdentityMapper;
import com.quanta.demo0.notification.mq.message.NotificationEventMessage;
import com.quanta.demo0.notification.mq.producer.NotificationEventProducer;
import com.quanta.demo0.platform.common.result.PageResult;
import com.quanta.demo0.user.service.UserAccountService;
import com.quanta.demo0.user.service.UserQueryService;
import com.quanta.demo0.user.vo.UserAccountVO;
import com.quanta.demo0.user.service.UserReadCacheInvalidator;
import com.quanta.demo0.platform.audit.service.AdminAuditRecorder;
import com.quanta.demo0.identity.service.IdentityExamService;
import com.quanta.demo0.identity.vo.IdentityDetailVO;
import com.quanta.demo0.identity.vo.IdentityExamVO;
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

/**
 * （补充）audit() 是全项目"事务边界"的范本：一次审核在同一事务里串了四件事——
 *
 * <pre>
 * BEGIN
 *  1. update tb_user_auth         —— 审核事实（audit_status / audit_remark / audit_time）
 *  2. update tb_user.auth_status  —— 展示态投影（UserAccountService#updateAuthStatus）
 *  3. insert 通知 Outbox 事件      —— createNotificationEvent 落库，事务提交后
 *                                    才由消费者投递 IDENTITY_AUDIT_RESULT 通知
 *  4. insert 管理员审计日志        —— recordSuccess 是 MANDATORY 传播，强制要求已有事务
 * COMMIT
 *  → afterCommit 才删缓存（认证快照 + 作者资料 + Redis security:verified:{userId}）
 * </pre>
 *
 * 【为什么缓存必须等提交后再清？】若先删缓存、事务还没提交，别的线程可能在
 * 这个间隙把旧值读回缓存，**旧状态会一直活到缓存过期**（Redis 那份 TTL 5 分钟，
 * RedisConstants#SECURITY_VERIFIED_TTL_MINUTES）。事务同步回调保证清缓存发生在
 * COMMIT 之后，这是"先写库、后失效"缓存模式的标配细节。
 */
@Service
@Slf4j
public class IdentityExamServiceImpl implements IdentityExamService {
    private static final String USER_AUTH_AGGREGATE_TYPE = "USER_AUTH";

    @Autowired
    private IdentityExamMapper identityExamMapper;
    @Autowired
    private IdentityMapper identityMapper;
    @Autowired
    private UserQueryService userQueryService;
    @Autowired
    private UserAccountService userAccountService;
    @Autowired
    private NotificationEventProducer notificationEventProducer;

    /**
     * 用户认证状态缓存失效器。
     */
    @Autowired
    private UserReadCacheInvalidator userReadCacheInvalidator;

    /**
     * 管理员审计记录器。
     */
    @Autowired
    private AdminAuditRecorder adminAuditRecorder;


    @Override
    public PageResult pageQuery(IdentityExamDTO identityExamDTO) {
        // 【坑】@ModelAttribute 绑定时 DTO 字段可能为 null（@Builder.Default 不生效），
        // 这里判空给默认值 1/10 是真正的兜底
        int pageNum = identityExamDTO.getPageNum() != null ? identityExamDTO.getPageNum() : 1;
        int pageSize = identityExamDTO.getPageSize() != null ? identityExamDTO.getPageSize() : 10;

        // PageHelper 靠 ThreadLocal 分页"下一条"SQL，list() 必须紧跟其后，
        // 中间若插入别的查询会被误分页
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
        // 详情 = tb_user_auth（认证资料）+ user 域账号资料（昵称/头像）两路拼装；
        // 本方法无写操作，真正承担多表一致性的是下面的 audit()
        if (authId == null) {

            throw new AuthFailedException("authId不能为空");
        }
        UserAuth auth = identityMapper.getUserAuthByAuthId(authId);
        if (auth == null) {
            throw new AuthFailedException("用户身份认证信息不存在");
        }
        Long userId = auth.getUserId();
        UserAccountVO user = userQueryService.getAccount(userId);
        if (user == null) {
            throw new AuthFailedException("用户不存在");
        }
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
        UserAuth auth = identityMapper.getUserAuthByAuthId(identityAuditDTO.getAuthId());
        if (auth == null) {
            throw new AuthFailedException("用户身份认证信息不存在");
        }
        //认证成功
        // 【边界】getAuditResult() 为 null 时，下面第一次比较因 Integer 自动拆箱直接 NPE，
        // 走不到 else 里的友好提示；非 1/2 的值才会落到 else 分支
        if (identityAuditDTO.getAuditResult() == AuditStatus.APPROVED.getCode()) {
            // 通过：只改状态和时间；【坑】不清旧 audit_remark——若该记录此前被驳回过，
            // 旧驳回文案会一直留在库里（动态 SQL 只更新非空字段）
            auth.setAuditTime(LocalDateTime.now());
            auth.setAuditStatus(AuditStatus.APPROVED.getCode());
            identityMapper.updateUserAuth(auth);
            userAccountService.updateAuthStatus(auth.getUserId(), UserAuthDisplayStatus.VERIFIED.getCode());
        } else if (identityAuditDTO.getAuditResult() == AuditStatus.REJECTED.getCode()) {
            //认证驳回
            // 驳回原因落库 + 时间戳；用户可在 /user/auth/status 看到原因并重新提交
            auth.setAuditStatus(AuditStatus.REJECTED.getCode());
            auth.setAuditRemark(identityAuditDTO.getAuditRemark());
            auth.setAuditTime(LocalDateTime.now());
            identityMapper.updateUserAuth(auth);
            userAccountService.updateAuthStatus(auth.getUserId(), UserAuthDisplayStatus.REJECTED.getCode());

        } else {
            throw new AuthFailedException("审核状态只能为" + AuditStatus.APPROVED.getCode() + " 或者 " + AuditStatus.REJECTED.getCode() + " 之间的");
        }
        String notifyContent = identityAuditDTO.getAuditResult() == AuditStatus.APPROVED.getCode()
                ? "你的身份认证已通过"
                : "你的身份认证未通过，原因：" + (identityAuditDTO.getAuditRemark() != null ? identityAuditDTO.getAuditRemark() : "");
        // 通过/驳回两套文案；payload 里带 authId/auditResult/auditRemark，
        // 小程序收到 IDENTITY_AUDIT_RESULT 通知后可凭 authId 跳详情（dev-seed 种子即此结构）

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
        // actorUserId=null 表示以"系统"名义发送；消息此刻只是写进 Outbox 表，
        // 事务提交后由消费者投递到 RabbitMQ（Outbox 可靠投递，见 platform 包）
        notificationEventProducer.createNotificationEvent(identityNotification, USER_AUTH_AGGREGATE_TYPE, identityAuditDTO.getAuthId());

        /*
         * 认证通过或驳回后，使旧的认证状态缓存失效。
         *
         * 此处只是登记事务提交回调：
         * 数据库事务成功提交后才会真正删除Redis缓存。
         */
        userReadCacheInvalidator.evictAllAfterCommit(auth.getUserId());

        // 审计：身份审核成功
        // recordSuccess 是 MANDATORY 传播——没有事务会直接报错，保证审计日志
        // 与业务同生共死（回滚时审计也不留"假成功"记录）；
        // 失败场景的兜底在控制器的 @AdminAudit 切面里（切面只记失败）
        adminAuditRecorder.recordSuccess(
                AdminAuditActionConstants.IDENTITY_AUDIT,
                "IDENTITY_AUTH",
                String.valueOf(identityAuditDTO.getAuthId()),
                "auditStatus=PENDING",
                "auditStatus=" + identityAuditDTO.getAuditResult()
        );

    }
}
