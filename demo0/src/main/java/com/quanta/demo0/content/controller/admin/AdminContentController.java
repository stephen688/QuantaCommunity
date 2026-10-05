package com.quanta.demo0.content.controller.admin;

import com.quanta.demo0.platform.audit.annotation.AdminAudit;
import com.quanta.demo0.platform.audit.constant.AdminAuditActionConstants;
import com.quanta.demo0.platform.security.constant.PermissionConstants;
import com.quanta.demo0.content.dto.ContentAdminQueryDTO;
import com.quanta.demo0.content.dto.ContentAuditDTO;
import com.quanta.demo0.interaction.dto.ContentReportHandleDTO;
import com.quanta.demo0.interaction.dto.ContentReportQueryDTO;
import com.quanta.demo0.platform.common.result.PageResult;
import com.quanta.demo0.platform.common.result.Result;
import com.quanta.demo0.content.service.AdminContentService;
import com.quanta.demo0.interaction.service.ReportGovernanceService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * 管理端 - 内容管理控制器
 * 路径前缀：/admin/content
 *
 * ============================================================
 * 【@AdminAudit：管理操作的"黑匣子"】
 * ============================================================
 * 每个写操作（audit/delete/handleReport）都贴了 @AdminAudit，
 * 把"谁、在什么时候、对哪个 targetId、做了什么 action"落审计表。
 * 注意 targetId 用的是 SpEL："#auditDTO.contentId" ——
 * 从方法参数里取值，注解才能对任意形状的 DTO 复用。
 *
 * 【面试追问：审计和业务是同一个事务吗？】
 * 审计记录**必须**跟业务同事务 —— "审核通过了但审计丢了"这种事在合规上不可接受。
 * 实现（AOP 切面）要在业务事务内写审计行，而不是 afterCommit 异步补。
 *
 * 【权限码粒度：读和审是两个权限点】
 * CONTENT_READ_ADMIN（看列表/举报）与 CONTENT_AUDIT（审核/处理举报）分开 ——
 * 可以给客服"只看不能审"的角色。**权限点的粒度 = 可能要分开授权的最小职责**。
 * 对比用户侧 Controller 全用 isAuthenticated()：管理端才需要细粒度权限模型，
 * 因为"谁能干什么"在管理端是真实的运营问题。
 */
@RestController
@RequestMapping("/admin/content")
@Slf4j
public class AdminContentController {

    @Autowired
    private AdminContentService adminContentService;
    @Autowired
    private ReportGovernanceService reportGovernanceService;

    /**
     * 分页查询内容列表
     *
     * 【同一个项目里为什么有两种分页？—— 用户侧游标，管理端页码】
     * 这里用 PageHelper 页码分页（pageNum/pageSize + total），用户侧 Feed 却用游标
     * （见 ContentController.recommend 的对比说明）。选型看消费方语义：
     *   - 运营后台要"跳页 + 总数 + 固定列宽表格"，页码分页是正确形状；
     *   - 数据在筛选条件下是小集合、并发插入不频繁，页码漂移在这里无害。
     * **分页方案跟着消费方走，没有全局最优**。
     *
     * 【注意 pageSize 没有上限收紧】
     * Bot 同步接口在 Service 里 clamp 到 200，这里没有 —— 因为调用方已过
     * CONTENT_READ_ADMIN 权限门槛，可信度高。**参数收紧的强度 = 调用方的不可信程度**；
     * 若这个接口将来开放给低信任角色，pageSize 就成了拖库/DoS 的抓手。
     *
     * 接口说明：
     * - 路径：GET /admin/content/page
     * - 权限：仅管理员可访问
     * - 支持按审核状态、内容类型筛选
     *
     * 请求参数（Query Param）：
     * - pageNum：页码，默认 1
     * - pageSize：每页数量，默认 10
     * - auditStatus：可选，审核状态（0-待审核 1-已通过 2-已驳回）
     * - contentType：可选，内容类型（1-生活求助 2-专业问答）

     * 返回数据：
     * - total：总记录数
     * - records：当前页内容列表（Content 实体）
     * 示例请求：
     * GET /admin/content/page?pageNum=1&pageSize=10&auditStatus=0
     */
    @PreAuthorize("hasAuthority('" + PermissionConstants.CONTENT_READ_ADMIN + "')")
    @GetMapping("/page")
    public Result<PageResult> page(ContentAdminQueryDTO query) {
        log.info("管理端分页查询内容，查询条件：{}", query);
        PageResult pageResult = adminContentService.pageQuery(query);
        return Result.success(pageResult);
    }



    /**
     * 审核帖子
     * 【面试点】审核通过/驳回后，ES 索引和向量库的同步不在这里做 ——
     * 由 Outbox 事件链路异步驱动。审核事务只负责审核状态本身，可见性交给下游。
     * 接口说明：
     * - 路径：POST /admin/content/audit
     * - 权限：仅管理员可访问
     * - 审核通过后：同步到 ES 和向量库
     * - 审核驳回后：从 ES 和向量库删除
     * 请求体（JSON）：
     * - contentId：内容 ID
     * - auditResult：审核结果（1-通过 2-驳回）
     * - rejectReason：驳回说明（可选，仅驳回时填写）
     * 示例请求：
     * POST /admin/content/audit
     * {
     *   "contentId": 1,
     *   "auditResult": 1
     * }
     *
     * 【重复提交同一审核结果是安全的】
     * auditResult 表达"置为某状态"而不是"执行某动作"：Service 里新旧状态相同
     * 就不发事件、不发通知（见 AdminContentServiceImpl.audit 的 visibilityChanged 判断），
     * 网络重试/运营手抖连点都不会产生重复副作用 —— **状态置位天然幂等，动作触发不是**。
     */
    @AdminAudit(
            action = AdminAuditActionConstants.CONTENT_AUDIT,
            targetType = "CONTENT",
            targetId = "#auditDTO.contentId"
    )
    @PreAuthorize("hasAuthority('" + PermissionConstants.CONTENT_AUDIT + "')")
    @PostMapping("/audit")
    public Result audit(@RequestBody ContentAuditDTO auditDTO) {
        log.info("管理端审核帖子，审核信息：{}", auditDTO);
        adminContentService.audit(auditDTO);
        return Result.success();
    }
    /**
     * 删除帖子
     * 【面试点】与用户侧删除（ContentCommandServiceImpl.deleteContent）是同一套
     * "MySQL 软删 → Outbox 事件 → afterCommit 清投影"的四层流程，唯一区别是
     * 跳过"操作者 = 发布者"的校验 —— 规则复用、差异最小化，而不是再写一份。
     * 接口说明：
     * - 路径：DELETE /admin/content/{contentId}
     * - 权限：仅管理员可访问
     * - 删除后：软删除内容，清理 Redis、ES、向量库、Feed 流
     * 路径参数：
     * - contentId：内容 ID
     * 示例请求：
     * DELETE /admin/content/1
     */
    @AdminAudit(
            action = AdminAuditActionConstants.CONTENT_DELETE,
            targetType = "CONTENT",
            targetId = "#contentId"
    )
    @PreAuthorize("hasAuthority('" + PermissionConstants.CONTENT_DELETE + "')")
    @DeleteMapping("/{contentId}")
    public Result delete(@PathVariable Long contentId) {
        log.info("管理端删除帖子，contentId={}", contentId);
        adminContentService.deleteContent(contentId);
        return Result.success();
    }

    /**
     * 分页查询帖子举报列表
     * 接口说明：
     * - 路径：GET /admin/content/report/page
     * - 权限：仅管理员可访问
     * - 支持按处理状态筛选
     * 请求参数（Query Param）：
     * - pageNum：页码，默认 1
     * - pageSize：每页数量，默认 10
     * - status：可选，处理状态（0-待处理 1-处理中 2-已处理 3-已驳回）
     * 返回数据：
     * - total：总记录数
     * - records：当前页举报列表（ContentReport 实体）
     * 示例请求：
     * GET /admin/content/report/page?pageNum=1&pageSize=10&status=0
     */
    @PreAuthorize("hasAuthority('" + PermissionConstants.CONTENT_READ_ADMIN + "')")
    @GetMapping("/report/page")
    public Result<PageResult> pageReport(ContentReportQueryDTO query) {
        log.info("管理端分页查询帖子举报，查询条件：{}", query);
        PageResult pageResult = reportGovernanceService.pageReport(query);
        return Result.success(pageResult);
    }


    /**
     * 处理帖子举报
     * 接口说明：
     * - 路径：POST /admin/content/report/handle
     * - 权限：仅管理员可访问
     * - 处理结果：1-删除帖子 2-警告用户 3-删除+警告 4-驳回举报
     * - 如果选择删除帖子，会自动执行删除操作
     * 请求体（JSON）：
     * - reportId：举报记录 ID
     * - handleResult：处理结果（1-删除帖子 2-警告用户 3-删除+警告 4-驳回举报）
     * - handleRemark：处理备注（可选）
     * 示例请求：
     * POST /admin/content/report/handle
     * {
     *   "reportId": 1,
     *   "handleResult": 1,
     *   "handleRemark": "内容违规，已删除"
     * }
     *
     * 【注意这是个"复合操作"】
     * handleResult 选 1（删帖）或 3（删帖+警告）时，Service 内部会级联调
     * AdminContentService.deleteContent —— 举报处理和删帖在同一个事务里，
     * 要么都生效要么都不生效。处理举报要求 CONTENT_AUDIT，包含删除的处置
     * 额外要求 CONTENT_DELETE，避免审核角色通过复合入口越过独立删除权限。
     */
    @AdminAudit(
            action = AdminAuditActionConstants.REPORT_HANDLE,
            targetType = "REPORT",
            targetId = "#handleDTO.reportId"
    )
    @PreAuthorize("hasAuthority('" + PermissionConstants.CONTENT_AUDIT + "') and "
            + "((#handleDTO.handleResult != 1 and #handleDTO.handleResult != 3) or "
            + "hasAuthority('" + PermissionConstants.CONTENT_DELETE + "'))")
    @PostMapping("/report/handle")
    public Result handleReport(@RequestBody ContentReportHandleDTO handleDTO) {
        log.info("管理端处理帖子举报，处理信息：{}", handleDTO);
        reportGovernanceService.handleReport(handleDTO);
        return Result.success();
    }
}
