package com.quanta.demo0.content.dto;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 管理端 - 内容审核入参
 *
 * ============================================================
 * 【auditResult 表达"目标状态"而不是"动作"——幂等的来源】
 * ============================================================
 * 用 Integer 1/2（= Content.audit_status 的状态码）而不是布尔或"approve/reject"指令，
 * 好处：审核接口语义是"把帖子置为某状态"，与 ContentMapper.update 直接对齐。
 * 重复提交同一结果是 no-op（新旧状态相同则不发事件不发通知，见
 * AdminContentServiceImpl.audit 的 visibilityChanged 判断）——
 * **状态置位天然幂等；如果传"执行通过"这种动作，就得自己做防重**。
 *
 * 【安全边界：这个类里没有审核人字段】
 * 谁审的，从 JWT（BaseContext）取，由 @AdminAudit AOP 落审计表 ——
 * 审核人绝不能由请求体声明，否则审计链路形同虚设。
 *
 * 【一个 DTO 服务两个审核场景】
 * 帖子审核用 contentId，回答审核用 answerId —— 合并成一个入参类而不是拆两个，
 * 因为两者的状态机（1-通过 2-驳回）和权限点（CONTENT_AUDIT）完全相同；
 * 差异只用 answerId 是否有值表达。
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class ContentAuditDTO {
    /**
     * 内容 ID
     * 帖子审核场景的主键；回答审核场景下是回答所属的问题帖 ID
     * （历史请求曾把它当回答 ID 传，见 resolveAnswerId 的兼容逻辑）。
     */
    private Long contentId;

    /**
     * 回答 ID（用于回答审核场景）
     * 服务端从 token 推导不出"审核对象是哪条回答"，这个字段必须由客户端给 ——
     * 但它能给什么由 @PreAuthorize(CONTENT_AUDIT) 划定，越权审核别人帖子会被参数校验/存在性检查拦住。
     */
    private Long answerId;

    /**
     * 审核结果：1-通过 2-驳回
     * 【坑】传 0（待审核）或其它值会被 Service 拒绝 —— 审核接口只允许状态前进，
     * "打回待审"不是这个接口的职责。
     */
    private Integer auditResult;

    /**
     * 驳回说明（可选，仅驳回时填写）
     * 会被拼进给作者的站内通知（"你的内容审核未通过，原因：xxx"，
     * 见 AdminContentServiceImpl.audit）——这是唯一一个会透出给用户的字段，
     * 写什么要想清楚：它面对的是被驳回的作者，不是审核员的内部备注。
     */
    private String rejectReason;

    /**
     * 回答审核场景下的目标 ID：
     * 优先使用 answerId；为兼容历史请求，若 answerId 为空则回退 contentId。
     * 【为什么要有兜底】老版本客户端曾把回答 ID 塞在 contentId 字段里传 ——
     * 服务端用"回退"消化字段错配，而不是强推所有调用方同步升级；
     * **兼容层放在解析点（一个方法），不要扩散到业务逻辑里**。
     */
    public Long resolveAnswerId() {
        return answerId != null ? answerId : contentId;
    }
}