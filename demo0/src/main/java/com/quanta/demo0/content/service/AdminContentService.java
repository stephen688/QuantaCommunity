package com.quanta.demo0.content.service;

import com.quanta.demo0.content.dto.ContentAdminQueryDTO;
import com.quanta.demo0.content.dto.ContentAuditDTO;
import com.quanta.demo0.platform.common.result.PageResult;

/**
 * 管理端内容治理端口（分页巡查、人工审核、违规下架删除）。
 *
 * ============================================================
 * 【调用方与鉴权契约】
 * ============================================================
 * 只允许管理端 Controller 暴露；管理员身份鉴权在 Controller/拦截器层完成，
 * 服务层不再重复校验（对比 ContentCommandService.deleteContent 要校验发布者本人）。
 * 【与 ContentAuditService 的本质区别】人工审核要支持"已通过→下架、已驳回→翻案"
 * 的状态回流，所以不做 CAS——幂等靠"新旧状态差分为 0 则零副作用"实现
 * （状态差分器的设计讲解见 AdminContentServiceImpl 类注释）。
 */
public interface AdminContentService {

    /** 管理端内容分页巡查：PageHelper 分页，返回 Content 实体（含全部审核状态，不受可见性过滤）。 */
    PageResult pageQuery(ContentAdminQueryDTO query);

    /**
     * 人工审核：auditResult 1=通过 2=驳回。
     * 【契约】按"旧状态 × 审核决定"差分出可见性变化，对称补齐曝光/下架、
     * Feed UPSERT/DELETE、ES 对账、专业区回答联动校准、用户通知与缓存失效；
     * 状态未变化时零副作用（幂等）；内容不存在或审核结果非法抛 ContentFailedException。
     * 【安全边界】请求体只有"结果 + 原因"，没有标题/正文等内容字段——帖子本体不可经此篡改。
     */
    void audit(ContentAuditDTO auditDTO);

    /** 管理端删除内容：与用户侧删除同套"四层清理"，仅跳过发布者校验（鉴权已前置在入口层）。 */
    void deleteContent(Long contentId);

}
