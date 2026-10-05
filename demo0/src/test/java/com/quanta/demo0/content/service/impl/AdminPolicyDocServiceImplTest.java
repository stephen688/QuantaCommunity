package com.quanta.demo0.content.service.impl;

import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.quanta.demo0.content.dto.BotPolicyDocDTO;
import com.quanta.demo0.content.dto.PolicyDocAdminQueryDTO;
import com.quanta.demo0.content.mapper.PolicyDocMapper;
import com.quanta.demo0.content.vo.PolicyDocAdminVO;
import com.quanta.demo0.platform.audit.constant.AdminAuditActionConstants;
import com.quanta.demo0.platform.audit.service.AdminAuditRecorder;
import com.quanta.demo0.platform.common.exception.NoFoundException;
import com.quanta.demo0.platform.common.result.PageResult;
import com.quanta.demo0.content.service.BotContentSyncService;
import com.quanta.demo0.content.exception.ContentFailedException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 政策知识库管理服务行为测试。
 *
 * <p>重点保护查询筛选收紧、写路径复用和软删审计；数据库 SQL 由后续 MySQL 集成验收覆盖。</p>
 */
@ExtendWith(MockitoExtension.class)
class AdminPolicyDocServiceImplTest {

    @Mock
    private PolicyDocMapper policyDocMapper;

    @Mock
    private BotContentSyncService botContentSyncService;

    @Mock
    private AdminAuditRecorder adminAuditRecorder;

    @InjectMocks
    private AdminPolicyDocServiceImpl service;

    @AfterEach
    void clearPageHelperState() {
        PageHelper.clearPage();
    }

    @Test
    void pageQueryClampsSizeNormalizesStatusAndReturnsPageResult() {
        PolicyDocAdminQueryDTO query = PolicyDocAdminQueryDTO.builder()
                .pageNum(0)
                .pageSize(999)
                .keyword("  scholarship  ")
                .status("active")
                .build();
        Page<PolicyDocAdminVO> page = new Page<>(1, 100);
        page.add(PolicyDocAdminVO.builder().docId("policy:scholarship").isDeleted(0).build());
        page.setTotal(1);
        when(policyDocMapper.pageAdmin(any(PolicyDocAdminQueryDTO.class))).thenReturn(page);

        PageResult result = service.pageQuery(query);

        assertThat(result.getTotal()).isEqualTo(1);
        assertThat(result.getRecords()).hasSize(1);
        ArgumentCaptor<PolicyDocAdminQueryDTO> queryCaptor =
                ArgumentCaptor.forClass(PolicyDocAdminQueryDTO.class);
        verify(policyDocMapper).pageAdmin(queryCaptor.capture());
        PolicyDocAdminQueryDTO normalized = queryCaptor.getValue();
        assertThat(normalized.getPageNum()).isEqualTo(1);
        assertThat(normalized.getPageSize()).isEqualTo(100);
        assertThat(normalized.getKeyword()).isEqualTo("scholarship");
        assertThat(normalized.getStatus()).isEqualTo("ACTIVE");
    }

    @Test
    void pageQueryRejectsUnknownStatusInsteadOfReturningUnfilteredRows() {
        PolicyDocAdminQueryDTO query = PolicyDocAdminQueryDTO.builder().status("archived").build();

        assertThatThrownBy(() -> service.pageQuery(query))
                .isInstanceOf(ContentFailedException.class)
                .hasMessageContaining("ACTIVE 或 DELETED");

        verify(policyDocMapper, never()).pageAdmin(any(PolicyDocAdminQueryDTO.class));
    }

    @Test
    void upsertUsesExistingBotWritePathAndAuditsRevivalWithoutDirectMapperWrite() {
        BotPolicyDocDTO dto = new BotPolicyDocDTO(" policy:scholarship ", "奖助学金", "新正文");
        String docId = "policy:scholarship";
        when(policyDocMapper.selectAdminDetail(docId))
                .thenReturn(PolicyDocAdminVO.builder()
                        .docId(docId)
                        .isDeleted(1)
                        .build());

        service.upsert(dto);

        verify(botContentSyncService).upsertPolicyDoc(new BotPolicyDocDTO(docId, dto.getTitle(), dto.getContent()));
        verify(policyDocMapper).selectAdminDetail(docId);
        verify(adminAuditRecorder).recordSuccess(
                AdminAuditActionConstants.POLICY_DOC_UPSERT,
                "POLICY_DOC",
                docId,
                "isDeleted=1",
                "isDeleted=0"
        );
    }

    @Test
    void softDeleteRejectsUnknownDocumentBeforeCallingBotWritePath() {
        when(policyDocMapper.selectAdminDetail("policy:missing")).thenReturn(null);

        assertThatThrownBy(() -> service.softDelete("policy:missing"))
                .isInstanceOf(NoFoundException.class)
                .hasMessage("政策文档不存在");

        verify(botContentSyncService, never()).softDeletePolicyDoc(any());
        verify(adminAuditRecorder, never()).recordSuccess(
                any(), any(), any(), any(), any());
    }

    @Test
    void softDeleteUsesExistingBotTombstonePathAndAuditsStateChange() {
        when(policyDocMapper.selectAdminDetail("policy:old"))
                .thenReturn(PolicyDocAdminVO.builder().docId("policy:old").isDeleted(0).build());

        service.softDelete(" policy:old ");

        verify(botContentSyncService).softDeletePolicyDoc("policy:old");
        verify(adminAuditRecorder).recordSuccess(
                AdminAuditActionConstants.POLICY_DOC_DELETE,
                "POLICY_DOC",
                "policy:old",
                "isDeleted=0",
                "isDeleted=1"
        );
    }
}
