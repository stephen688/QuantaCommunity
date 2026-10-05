package com.quanta.demo0.content.service;

/**
 * 内容审核状态机端口（机审链路的自动通过/驳回入口）。
 *
 * ============================================================
 * 【与 AdminContentService.audit 的分工——两套审核，两种并发策略】
 * ============================================================
 * 本接口是机审消费者（AI 审核结果回调）、发布侧自动通过、定时补审共用的唯一出口，
 * 用 CAS（仅 PENDING 可流转）保证"一个帖子只被自动审一次"，重复调用幂等；
 * 管理端人工审核走 AdminContentService——它要支持 1↔2 的状态回流，故意不用 CAS。
 * **入口可以多，状态机必须只有一个**：通过/驳回的全部下游副作用只在实现类实现一次，
 * 任何来源都不会漏掉某个下游（实现与逐行讲解见 ContentAuditServiceImpl）。
 */
public interface ContentAuditService {

    /**
     * 审核通过：更新状态并执行曝光副作用(如发 Feed、写 ES 等)
     *
     * 【接口契约（实现见 ContentAuditServiceImpl）】
     * 【重复消费会怎样】CAS（updateAuditStatusIfPending）保证只有第一次调用真正生效，
     * 后续重复调用 0 行命中、静默 return——幂等消费者不把"已做过"当错误。
     * 【失败语义】内容不存在同样静默 return（仅日志）；方法无返回值，
     * 调用方无法也无需区分"本次通过"还是"早已通过"。
     * 【副作用清单】主题打标 Outbox + Feed UPSERT + ES 对账 + 推荐池曝光 + 用户通知，
     * 全部与状态更新在同一个事务里登记/执行。
     */
    void approveContent(Long contentId);

    /**
     * 审核驳回：仅当仍为待审时更新状态并通知作者
     *
     * 【接口契约】CAS 限定只有 PENDING 能被驳回；被驳回的帖子从未进过推荐池/Feed，
     * 所以没有撤销动作——只发 ES 对账事件（防索引残留）+ 通知作者（可带原因）。
     * rejectReason 可为空；重复调用幂等（同 approveContent，0 行静默跳过）。
     */
    void rejectContent(Long contentId, String rejectReason);
}
