package com.quanta.demo0.content.service.impl;

import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.quanta.demo0.content.dto.BotPolicyDocDTO;
import com.quanta.demo0.content.dto.PolicyDocAdminQueryDTO;
import com.quanta.demo0.content.exception.ContentFailedException;
import com.quanta.demo0.content.mapper.PolicyDocMapper;
import com.quanta.demo0.content.service.AdminPolicyDocService;
import com.quanta.demo0.content.service.BotContentSyncService;
import com.quanta.demo0.content.vo.PolicyDocAdminVO;
import com.quanta.demo0.platform.audit.constant.AdminAuditActionConstants;
import com.quanta.demo0.platform.audit.service.AdminAuditRecorder;
import com.quanta.demo0.platform.common.exception.NoFoundException;
import com.quanta.demo0.platform.common.result.PageResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

/**
 * 政策知识库管理端服务实现。
 *
 * <p>查询通过专用 PolicyDocMapper 完成，写操作调用既有 BotContentSyncService，确保管理端和
 * Bot 运营入口共享相同的 docId/title/content 校验、复活和水位线墓碑语义。审计成功记录与源文档写入
 * 处于同一事务；失败审计由 Controller 上的 AdminAudit 切面负责。</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class AdminPolicyDocServiceImpl implements AdminPolicyDocService {

    private static final int MAX_PAGE_SIZE = 100;

    private final PolicyDocMapper policyDocMapper;
    private final BotContentSyncService botContentSyncService;
    private final AdminAuditRecorder adminAuditRecorder;

    /**
     * 分页查询源文档。
     *
     * @param query 关键词、状态和分页参数；null 按默认分页处理
     * @return 统一管理端分页结构
     * @throws ContentFailedException status 不是 ACTIVE、DELETED 或空值时抛出
     */
    @Override
    @Transactional(readOnly = true)
    public PageResult pageQuery(PolicyDocAdminQueryDTO query) {
        PolicyDocAdminQueryDTO normalized = normalizeQuery(query);
        PageHelper.startPage(normalized.getPageNum(), normalized.getPageSize());
        Page<PolicyDocAdminVO> page = policyDocMapper.pageAdmin(normalized);
        return new PageResult(page.getTotal(), page.getResult());
    }

    /**
     * 查询详情，包含已删除墓碑，以便运营明确确认恢复。
     *
     * @param docId 稳定业务文档 ID
     * @return 源文档管理视图
     * @throws ContentFailedException docId 为空
     * @throws NoFoundException 文档不存在
     */
    @Override
    @Transactional(readOnly = true)
    public PolicyDocAdminVO getDetail(String docId) {
        String normalizedDocId = requireDocId(docId);
        PolicyDocAdminVO document = policyDocMapper.selectAdminDetail(normalizedDocId);
        if (document == null) {
            throw new NoFoundException("政策文档不存在");
        }
        return document;
    }

    /**
     * 新增、更新或恢复文档，并写成功审计。
     *
     * <p>校验和实际写入交给既有 BotContentSyncService，不在此复制规则；已删除文档通过同一 upsert
     * 路径恢复，并保持 update_time 水位线变更，供 Bot 后续增量拉取。</p>
     */
    @Override
    @Transactional
    public void upsert(BotPolicyDocDTO dto) {
        if (dto == null) {
            throw new ContentFailedException("政策文档参数不能为空");
        }
        String docId = requireDocId(dto.getDocId());
        PolicyDocAdminVO before = getExisting(docId);
        // 既有服务负责 docId/title/content 严格校验及唯一键 upsert，避免两套政策写逻辑漂移。
        // 查询、写入与成功审计必须使用同一业务 ID，避免空白变体成为不可正常编辑/删除的源文档。
        botContentSyncService.upsertPolicyDoc(new BotPolicyDocDTO(docId, dto.getTitle(), dto.getContent()));
        String beforeSummary = summarize(before);
        String afterSummary = "isDeleted=0";
        adminAuditRecorder.recordSuccess(
                AdminAuditActionConstants.POLICY_DOC_UPSERT,
                "POLICY_DOC",
                docId,
                beforeSummary,
                afterSummary
        );
        log.info("管理端保存政策源文档：docId={}，before={}", docId, beforeSummary);
    }

    /**
     * 软删除文档并写成功审计；墓碑保留给同步链路传播删除事件。
     *
     * @param docId 稳定业务文档 ID
     * @throws NoFoundException 文档不存在
     */
    @Override
    @Transactional
    public void softDelete(String docId) {
        String normalizedDocId = requireDocId(docId);
        PolicyDocAdminVO before = getExisting(normalizedDocId);
        if (before == null) {
            throw new NoFoundException("政策文档不存在");
        }
        botContentSyncService.softDeletePolicyDoc(normalizedDocId);
        adminAuditRecorder.recordSuccess(
                AdminAuditActionConstants.POLICY_DOC_DELETE,
                "POLICY_DOC",
                normalizedDocId,
                summarize(before),
                "isDeleted=1"
        );
        log.info("管理端软删除政策源文档：docId={}，alreadyDeleted={}", normalizedDocId,
                Integer.valueOf(1).equals(before.getIsDeleted()));
    }

    private PolicyDocAdminVO getExisting(String docId) {
        String normalizedDocId = requireDocId(docId);
        return policyDocMapper.selectAdminDetail(normalizedDocId);
    }

    private PolicyDocAdminQueryDTO normalizeQuery(PolicyDocAdminQueryDTO query) {
        PolicyDocAdminQueryDTO normalized = query == null ? new PolicyDocAdminQueryDTO() : query;
        normalized.setPageNum(Math.max(normalized.getPageNum() == null ? 1 : normalized.getPageNum(), 1));
        normalized.setPageSize(Math.min(Math.max(normalized.getPageSize() == null ? 10 : normalized.getPageSize(), 1), MAX_PAGE_SIZE));
        if (normalized.getKeyword() != null) {
            normalized.setKeyword(normalized.getKeyword().trim());
        }
        if (normalized.getStatus() != null && !normalized.getStatus().isBlank()) {
            String status = normalized.getStatus().trim().toUpperCase(Locale.ROOT);
            if (!"ACTIVE".equals(status) && !"DELETED".equals(status)) {
                throw new ContentFailedException("政策文档状态只能为 ACTIVE 或 DELETED");
            }
            normalized.setStatus(status);
        } else {
            normalized.setStatus(null);
        }
        return normalized;
    }

    private String requireDocId(String docId) {
        if (docId == null || docId.isBlank()) {
            throw new ContentFailedException("docId 不能为空");
        }
        return docId.trim();
    }

    private String summarize(PolicyDocAdminVO document) {
        if (document == null) {
            return "absent";
        }
        return "isDeleted=" + document.getIsDeleted();
    }
}
