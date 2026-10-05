package com.quanta.demo0.identity.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.LocalDate;

/**
 * 管理端审核列表的查询参数（GET /admin/identityExam/page，@ModelAttribute 绑定 query 参数）。
 *
 * 【坑】@Builder.Default 只在通过 builder 构造对象时生效；走 @ModelAttribute 反序列化
 * 时如果前端不传 pageNum/pageSize，字段是 null，真正的兜底是
 * IdentityExamServiceImpl#pageQuery 里的判空（默认 1 / 10）。
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class IdentityExamDTO {
    /**
     * 页码
     */
    @Builder.Default
    private Integer pageNum=1 ;

    /**
     * 每页数量*
     *
     */
    @Builder.Default
   private Integer pageSize=10 ;

    /**
     * 审核状态 (可选)
     */
    // 可选过滤条件，取值同 AuditStatus（0 待审核 / 1 已通过 / 2 已驳回），不传查全部
    private Integer auditStatus;

/**
 * 开始时间
 */
    private LocalDate startTime;

    /**
     * 结束时间
     */
    // 时间过滤落在 tb_user_auth.create_time（申请提交时间）上；
    // endTime 含当天，XML 用 DATE_ADD(#{endTime}, INTERVAL 1 DAY) 实现闭区间
    private LocalDate endTime;
}
