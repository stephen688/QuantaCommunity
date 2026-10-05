package com.quanta.demo0.identity.entity;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 用户认证信息
 * */

/**
 * tb_user_auth 表的认证申请记录，一个用户最多一条（IdentityMapper#getUserAuthByUserId
 * 返回单个实体而不是 List）。
 *
 * 在链路中的角色：用户经 POST /user/auth/add 提交这里的五项文字资料，管理员在
 * /admin/identityExam/audit 审的就是这条记录；tb_user.auth_status 只是它的"展示态"投影。
 *
 * ============================================================
 * 【为什么实体直接当接口返回值，而不是再造一层 VO？】
 * ============================================================
 * IdentityController 的 /auth/add、/auth/detail 直接返回本实体，
 * 所以每个时间字段都挂了 @JsonFormat 保证出参格式统一。
 * 安全靠两道闸兜底：入参 {@link com.quanta.demo0.identity.dto.UserAuthDTO}
 * 根本没有 userId/auditStatus 字段（userId 一律取登录态，客户端改不了）；
 * /auth/detail 只放行 auditStatus=已通过的记录（IdentityServiceImpl#getAuthDetail）。
 * **普通用户互相看不到这些资料**——公开资料接口只出昵称和头像（user 域 UserInfoVO），
 * 真实姓名/学号只出现在本人详情和管理端审核详情（需 IDENTITY_AUDIT 权限）里。
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class UserAuth implements Serializable {


    /**
     * 认证信息实体类
     */

        /**
         * 认证记录ID（主键）
         */
        // 由 insertUserAuth 的 useGeneratedKeys 在插入后回填（IdentityMapper.xml）
        private Long authId;

        /**
         * 关联用户ID（唯一）
         */
        // 服务端从登录态取（BaseContext），DTO 里没有该字段，客户端传不了
        private Long userId;

        /**
         * 身份类型：1-在校成员 2-历届校友
         */
        private Integer identityType;

        /**
         * 真实姓名
         */
        private String realName;

        /**
         * 学号/校友编号
         */
        private String schoolId;

        /**
         * Quanta届数
         */
        private String quantaBatch;

        /**
         * 所属部门
         */
        private String quantaDepartment;

        /**
         * 审核状态：0-待审核 1-已通过 2-已驳回
         */
        // 码值与 platform.common.enums.AuditStatus 一致；注意与 tb_user.auth_status
        // 的展示态（UserAuthDisplayStatus 0/1/2/3）是两套编码，别混用
        private Integer auditStatus;

        /**
         * 审核备注/驳回原因
         */
        // 驳回时会写库，并通过 IDENTITY_AUDIT_RESULT 通知与 /auth/status 回给用户本人；
        // 动态 SQL 只更新非空字段，置 null 无法清掉这一列（见 IdentityMapper.xml updateUserAuth）
        private String auditRemark;

        /**
         * 审核时间
         */
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        private LocalDateTime auditTime;

        /**
         * 申请提交时间
         */
            // 同时是管理端审核列表的默认排序键（IdentityExamMapper.xml ORDER BY create_time DESC）
            @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        private LocalDateTime createTime;

        /**
         * 更新时间
         */
            @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        private LocalDateTime updateTime;
    }


