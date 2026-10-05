package com.quanta.demo0.content.service.impl;

import com.quanta.demo0.content.entity.Content;
import com.quanta.demo0.content.entity.ContentImage;
import com.quanta.demo0.content.mapper.ContentMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** 跨域快照迁移契约：保留原查询、标签空态、计数和分页可见性。 */
class ContentQueryServiceImplSnapshotTest {
    @Test
    void rawSnapshotPreservesUnapprovedFactsAndNullableTags() {
        ContentMapper mapper = mock(ContentMapper.class);
        ContentQueryServiceImpl service = service(mapper);
        LocalDateTime updated = LocalDateTime.of(2026, 9, 29, 12, 0);
        when(mapper.selectById(5L)).thenReturn(Content.builder()
                .contentId(5L).auditStatus(0).isDeleted(0).tags(null)
                .liked(3).commentCount(4).collectCount(2).updateTime(updated).build());

        var snapshot = service.getContentSnapshot(5L);

        assertThat(snapshot.getAuditStatus()).isZero();
        assertThat(snapshot.getTags()).isNull();
        assertThat(snapshot.getLikedCount()).isEqualTo(3);
        assertThat(snapshot.getCommentCount()).isEqualTo(4);
        assertThat(snapshot.getCollectCount()).isEqualTo(2);
        assertThat(snapshot.getUpdateTime()).isEqualTo(updated);
        verify(mapper).selectById(5L);
    }

    @Test
    void approvedRagPaginationUsesApprovedSqlBeforeLimit() {
        ContentMapper mapper = mock(ContentMapper.class);
        ContentQueryServiceImpl service = service(mapper);
        when(mapper.selectApprovedForRecommendWarmup(100, 100)).thenReturn(List.of(
                Content.builder().contentId(5L).auditStatus(1).isDeleted(0).tags("[]").build()));

        var batch = service.getApprovedContentSnapshotsForReindex(100, 100);

        assertThat(batch).hasSize(1);
        assertThat(batch.get(0).getTags()).isEqualTo("[]");
        verify(mapper).selectApprovedForRecommendWarmup(100, 100);
        verify(mapper, never()).selectAllForReindex(anyInt(), anyInt());
    }

    @Test
    void imageFactsPreserveRawSequenceWhileAuditUrlsRemainFiltered() {
        ContentMapper mapper = mock(ContentMapper.class);
        ContentQueryServiceImpl service = service(mapper);
        when(mapper.selectImagesByContentIds(5L)).thenReturn(List.of(
                ContentImage.builder().imageUrl("https://example.test/image").build(),
                ContentImage.builder().imageUrl("").build(),
                ContentImage.builder().imageUrl(null).build()));

        assertThat(service.getContentFactImageUrls(5L))
                .containsExactly("https://example.test/image", "", null);
        assertThat(service.getContentImageUrls(5L))
                .containsExactly("https://example.test/image");
    }

    @Test
    void batchImageUrlsGroupsByContentAndKeepsDisplayAndFactPolicies() {
        ContentMapper mapper = mock(ContentMapper.class);
        ContentQueryServiceImpl service = service(mapper);
        when(mapper.selectImagesBatchByContentIds(anyList())).thenReturn(List.of(
                ContentImage.builder().contentId(2L).imageUrl("https://example.test/two").sort(0).build(),
                ContentImage.builder().contentId(1L).imageUrl("https://example.test/one").sort(0).build(),
                ContentImage.builder().contentId(1L).imageUrl(" ").sort(1).build(),
                ContentImage.builder().contentId(1L).imageUrl(null).sort(2).build(),
                ContentImage.builder().contentId(1L).imageUrl("https://example.test/one").sort(3).build()));

        var display = service.getContentImageUrlsBatch(Arrays.asList(1L, null, 2L, 1L, 3L));
        var facts = service.getContentFactImageUrlsBatch(Arrays.asList(1L, null, 2L, 1L, 3L));

        assertThat(display).containsEntry(1L, List.of(
                        "https://example.test/one", "https://example.test/one"))
                .containsEntry(2L, List.of("https://example.test/two"))
                .containsEntry(3L, List.of());
        assertThat(facts).containsEntry(1L, Arrays.asList(
                        "https://example.test/one", " ", null, "https://example.test/one"))
                .containsEntry(2L, List.of("https://example.test/two"))
                .containsEntry(3L, List.of());
        verify(mapper, times(2)).selectImagesBatchByContentIds(anyList());
    }

    @Test
    void batchImageUrlsSkipsDatabaseForEmptyOrNullOnlyIds() {
        ContentMapper mapper = mock(ContentMapper.class);
        ContentQueryServiceImpl service = service(mapper);

        assertThat(service.getContentImageUrlsBatch(List.of())).isEmpty();
        assertThat(service.getContentFactImageUrlsBatch(Arrays.asList(null, null))).isEmpty();

        verifyNoInteractions(mapper);
    }

    private ContentQueryServiceImpl service(ContentMapper mapper) {
        ContentQueryServiceImpl service = new ContentQueryServiceImpl();
        ReflectionTestUtils.setField(service, "contentMapper", mapper);
        return service;
    }
}
