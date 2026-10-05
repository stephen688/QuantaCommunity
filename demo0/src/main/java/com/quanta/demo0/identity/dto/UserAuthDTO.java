package com.quanta.demo0.identity.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 用户提交认证申请的入参（POST /user/auth/add，{@code IdentityController#addUserAuth}）。
 *
 * ============================================================
 * 【为什么 DTO 里没有 userId 和审核状态字段？】
 * ============================================================
 * 这两样都是服务端的权威数据，绝不能让客户端填：
 * userId 从登录态 BaseContext 取（token 推导，**用户不可能替别人提交认证**）；
 * auditStatus 由服务端强制置为待审核（IdentityServiceImpl#addUserAuth）。
 * 反过来想：如果这里放开 userId 字段，越权给任意用户挂认证只需要改一个请求参数——
 * **入参少一个字段，就少一条要防的攻击路径**。
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class UserAuthDTO implements Serializable {

    //序列化版本号，确保反序列化时类的一致性
    private static final long serialVersionUID = 1L;


    /**
     * 身份类型：1-在校成员 2-历届校友
     */
    // 服务端只校验非空、不校验取值范围（IdentityServiceImpl#addUserAuth），
    // 乱填的值会原样入库，最终由管理员人工审核把关
    private Integer identityType;


    /**
     * 真实姓名
     */
    private String realName;


    /**
     * 学号
     */
    private String schoolId;


    /**
     * 届数
     */
    private String quantaBatch;


    /**
     * 部门
     */
    private String quantaDepartment;


}
