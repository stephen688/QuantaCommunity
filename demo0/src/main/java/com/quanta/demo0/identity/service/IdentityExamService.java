package com.quanta.demo0.identity.service;

import com.quanta.demo0.identity.dto.IdentityAuditDTO;
import com.quanta.demo0.identity.dto.IdentityExamDTO;
import com.quanta.demo0.platform.common.result.PageResult;
import com.quanta.demo0.identity.vo.IdentityDetailVO;

/**
 * 管理端身份审核服务。
 *
 * 【先纠正一个望文生义】"Exam"指管理员对认证申请的**查验/审核**，
 * 不是在线考试——本模块没有题目、判分或防作弊逻辑，认证资料只有五项文字信息，
 * 结论由管理员人工给出。
 *
 * 三个方法构成审核工作台的闭环：pageQuery 列表 → getDetailById 详情 → audit 出结论
 * （改认证表状态 + 回写用户展示态 + 发通知 + 记审计）。
 */
public interface IdentityExamService {
    /** 分页查询认证申请列表（PageHelper 分页，条件见 IdentityExamDTO）。 */
    PageResult pageQuery(IdentityExamDTO identityExamDTO);

    /** 按 authId 查审核详情：认证资料 + 用户昵称头像拼装。 */
    IdentityDetailVO getDetailById(Long authId);

    /** 审核出结论（1 通过 / 2 驳回），事务内联动展示态、通知 Outbox 和审计日志。 */
    void audit(IdentityAuditDTO identityAuditDTO);
}
