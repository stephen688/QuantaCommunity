package com.quanta.demo0.service;

import com.quanta.demo0.content.vo.BotSyncDocVO;
import com.quanta.demo0.content.vo.BotSyncPageVO;
import com.quanta.demo0.content.dto.BotPolicyDocDTO;
import com.quanta.demo0.content.entity.BotPolicyDoc;
import com.quanta.demo0.content.exception.ContentFailedException;
import com.quanta.demo0.content.mapper.BotContentSyncMapper;
import com.quanta.demo0.service.Impl.BotContentSyncServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 内容源同步服务单元测试（C-3）。 */
@ExtendWith(MockitoExtension.class)
class BotContentSyncServiceImplTest {

    @Mock
    private BotContentSyncMapper botContentSyncMapper;

    @InjectMocks
    private BotContentSyncServiceImpl botContentSyncService;

    private static BotSyncDocVO doc(String docId, String docKind, String status) {
        BotSyncDocVO value = new BotSyncDocVO();
        value.setDocId(docId);
        value.setDocKind(docKind);
        value.setContentId(docId.startsWith("content:") ? 9001L : null);
        value.setAnswerId(docId.startsWith("answer:") ? 5L : null);
        value.setTitle("标题");
        value.setContent("正文");
        value.setCreateTime("2026-09-18 10:00:00");
        value.setUpdateTime("2026-09-18 10:30:00");
        value.setUpdatedAt("2026-09-18 10:30:00");
        value.setStatus(status);
        return value;
    }

    @Test
    void syncReturnsItemsAndPreservesDeletedTombstone() {
        when(botContentSyncMapper.countSync(any(LocalDateTime.class))).thenReturn(2L);
        when(botContentSyncMapper.selectSyncBatch(any(LocalDateTime.class), anyInt(), anyInt()))
                .thenReturn(List.of(
                        doc("content:9001", "POST", "active"),
                        doc("content:9002", "POST", "deleted")));

        BotSyncPageVO result = botContentSyncService.getSync("1970-01-01", 1, 50);

        assertThat(result.getItems()).hasSize(2);
        assertThat(result.getItems().get(1).getStatus()).isEqualTo("deleted");
        assertThat(result.getItems().get(1).getDocId()).isEqualTo("content:9002");
        assertThat(result.isHasMore()).isFalse();
    }

    @Test
    void syncComputesHasMoreFromTotalAndNormalizedPage() {
        when(botContentSyncMapper.countSync(any(LocalDateTime.class))).thenReturn(120L);
        when(botContentSyncMapper.selectSyncBatch(any(LocalDateTime.class), anyInt(), anyInt()))
                .thenReturn(List.of());

        assertThat(botContentSyncService.getSync("1970-01-01", 1, 50).isHasMore()).isTrue();
        assertThat(botContentSyncService.getSync("1970-01-01", 3, 50).isHasMore()).isFalse();
    }

    @Test
    void syncParsesSupportedSinceFormatsAndRejectsGarbage() {
        when(botContentSyncMapper.countSync(any(LocalDateTime.class))).thenReturn(0L);
        when(botContentSyncMapper.selectSyncBatch(any(LocalDateTime.class), anyInt(), anyInt()))
                .thenReturn(List.of());

        botContentSyncService.getSync("2026-09-18", 1, 50);
        botContentSyncService.getSync("2026-09-18 10:00:00", 1, 50);

        ArgumentCaptor<LocalDateTime> sinceCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(botContentSyncMapper, times(2)).countSync(sinceCaptor.capture());
        assertThat(sinceCaptor.getAllValues().get(0))
                .isEqualTo(LocalDateTime.of(2026, 9, 18, 0, 0));
        assertThat(sinceCaptor.getAllValues().get(1))
                .isEqualTo(LocalDateTime.of(2026, 9, 18, 10, 0));
        assertThatThrownBy(() -> botContentSyncService.getSync("not-a-date", 1, 50))
                .isInstanceOf(ContentFailedException.class)
                .hasMessageContaining("since 格式非法");
    }

    @Test
    void upsertPolicyDocInsertsWhenDocumentIsAbsent() {
        when(botContentSyncMapper.selectByDocId("policy-scholarship")).thenReturn(null);

        botContentSyncService.upsertPolicyDoc(new BotPolicyDocDTO(
                "policy-scholarship", "奖助学金评审流程", "每年 9 月启动"));

        verify(botContentSyncMapper).insertPolicyDoc(any(BotPolicyDoc.class));
        verify(botContentSyncMapper, never()).updatePolicyDocByDocId(any(BotPolicyDoc.class));
    }

    @Test
    void upsertPolicyDocUpdatesAndRevivesExistingDocument() {
        when(botContentSyncMapper.selectByDocId("policy-scholarship"))
                .thenReturn(BotPolicyDoc.builder().id(1L).docId("policy-scholarship").build());

        botContentSyncService.upsertPolicyDoc(new BotPolicyDocDTO(
                "policy-scholarship", "奖助学金评审流程（修订）", "新正文"));

        verify(botContentSyncMapper).updatePolicyDocByDocId(any(BotPolicyDoc.class));
        verify(botContentSyncMapper, never()).insertPolicyDoc(any(BotPolicyDoc.class));
    }

    @Test
    void softDeletePolicyDocDelegatesToMapper() {
        when(botContentSyncMapper.softDeletePolicyDocByDocId("policy-x")).thenReturn(1);

        botContentSyncService.softDeletePolicyDoc("policy-x");

        verify(botContentSyncMapper).softDeletePolicyDocByDocId("policy-x");
    }
}
