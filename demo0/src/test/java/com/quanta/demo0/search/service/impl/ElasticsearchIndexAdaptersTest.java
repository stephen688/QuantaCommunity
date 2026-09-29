package com.quanta.demo0.search.service.impl;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ErrorCause;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.DeleteRequest;
import co.elastic.clients.elasticsearch.core.DeleteResponse;
import co.elastic.clients.elasticsearch.core.IndexRequest;
import co.elastic.clients.elasticsearch.core.IndexResponse;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.core.search.HitsMetadata;
import co.elastic.clients.elasticsearch.core.search.TotalHits;
import com.github.pagehelper.Page;
import com.quanta.demo0.content.vo.ContentSnapshotVO;
import com.quanta.demo0.answer.vo.AnswerSnapshotVO;
import com.quanta.demo0.search.es.document.AnswerDocument;
import com.quanta.demo0.search.es.document.ContentDocument;
import com.quanta.demo0.search.es.mapper.AnswerDocumentMapper;
import com.quanta.demo0.search.es.mapper.ContentDocumentMapper;
import com.quanta.demo0.search.es.query.ElasticsearchQueryFactory;
import com.quanta.demo0.search.exception.SearchFailedException;
import com.quanta.demo0.content.service.ContentQueryService;
import com.quanta.demo0.answer.service.AnswerQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ES 服务核心契约测试。
 *
 * 边界：只锁定迁移时最容易改变的索引文档、查询语义、bulk 失败传播和 RAG 降级；
 * 不重复测试 Java Client 自身的 builder/getter 行为。
 */
@ExtendWith(MockitoExtension.class)
class ElasticsearchIndexAdaptersTest {

    @Mock
    private ElasticsearchClient client;

    @Mock
    private ContentQueryService contentQueryService;

    @Mock
    private AnswerQueryService answerQueryService;

    private ContentIndexServiceImpl contentService;

    private AnswerSearchServiceImpl answerService;

    private ContentDocumentMapper contentDocumentMapper;

    private AnswerDocumentMapper answerDocumentMapper;

    @BeforeEach
    void setUp() {
        ElasticsearchQueryFactory queryFactory = new ElasticsearchQueryFactory();
        contentDocumentMapper = new ContentDocumentMapper(client, queryFactory);
        answerDocumentMapper = new AnswerDocumentMapper(client, queryFactory);
        contentService = new ContentIndexServiceImpl(contentQueryService, contentDocumentMapper);
        answerService = new AnswerSearchServiceImpl(answerQueryService, contentQueryService, answerDocumentMapper);
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void visibleContentKeepsExistingIndexAndDocumentShape() throws Exception {
        LocalDateTime createdAt = LocalDateTime.of(2026, 9, 26, 10, 30);
        ContentSnapshotVO content = visibleContent(42L, createdAt);
        when(contentQueryService.getContentSnapshot(42L)).thenReturn(content);
        when(client.index(any(IndexRequest.class))).thenReturn(mock(IndexResponse.class));

        contentService.upsertByContentId(42L);

        ArgumentCaptor<IndexRequest> captor = ArgumentCaptor.forClass(IndexRequest.class);
        verify(client).index(captor.capture());
        IndexRequest<Map<String, Object>> request = captor.getValue();
        assertThat(request.index()).isEqualTo("content");
        assertThat(request.id()).isEqualTo("42");
        assertThat(request.document()).containsExactlyInAnyOrderEntriesOf(Map.ofEntries(
                Map.entry("contentId", 42L),
                Map.entry("contentType", 2),
                Map.entry("title", "ES8 迁移标题"),
                Map.entry("content", "ES8 迁移正文"),
                Map.entry("publishUserId", 7L),
                Map.entry("auditStatus", 1),
                Map.entry("liked", 3),
                Map.entry("collectCount", 4),
                Map.entry("commentCount", 5),
                Map.entry("createTime", createdAt),
                Map.entry("isDeleted", 0)
        ));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void invisibleContentsUseDeleteRequestsInsteadOfIndexRequests() throws Exception {
        ContentSnapshotVO pending = visibleContent(101L, LocalDateTime.of(2026, 9, 26, 10, 31));
        pending.setAuditStatus(0);
        ContentSnapshotVO deleted = visibleContent(102L, LocalDateTime.of(2026, 9, 26, 10, 32));
        deleted.setIsDeleted(1);
        when(contentQueryService.getContentSnapshot(101L)).thenReturn(pending);
        when(contentQueryService.getContentSnapshot(102L)).thenReturn(deleted);
        when(client.delete(any(DeleteRequest.class))).thenReturn(mock(DeleteResponse.class));

        contentService.upsertByContentId(101L);
        contentService.upsertByContentId(102L);

        ArgumentCaptor<DeleteRequest> captor = ArgumentCaptor.forClass(DeleteRequest.class);
        verify(client, times(2)).delete(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(DeleteRequest::index, DeleteRequest::id)
                .containsExactly(tuple("content", "101"), tuple("content", "102"));
        verify(client, never()).index(any(IndexRequest.class));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void invisibleAnswersOrParentsUseDeleteRequestsInsteadOfIndexRequests() throws Exception {
        AnswerSnapshotVO pendingAnswer = visibleAnswer(201L, 401L);
        pendingAnswer.setAuditStatus(0);
        AnswerSnapshotVO deletedAnswer = visibleAnswer(202L, 402L);
        deletedAnswer.setIsDeleted(1);
        AnswerSnapshotVO answerWithPendingQuestion = visibleAnswer(203L, 403L);
        AnswerSnapshotVO answerWithDeletedQuestion = visibleAnswer(204L, 404L);
        ContentSnapshotVO pendingQuestion = visibleContent(403L, LocalDateTime.of(2026, 9, 26, 10, 33));
        pendingQuestion.setAuditStatus(2);
        ContentSnapshotVO deletedQuestion = visibleContent(404L, LocalDateTime.of(2026, 9, 26, 10, 34));
        deletedQuestion.setIsDeleted(1);
        when(answerQueryService.getAnswerSnapshot(201L)).thenReturn(pendingAnswer);
        when(answerQueryService.getAnswerSnapshot(202L)).thenReturn(deletedAnswer);
        when(answerQueryService.getAnswerSnapshot(203L)).thenReturn(answerWithPendingQuestion);
        when(answerQueryService.getAnswerSnapshot(204L)).thenReturn(answerWithDeletedQuestion);
        when(contentQueryService.getContentSnapshot(403L)).thenReturn(pendingQuestion);
        when(contentQueryService.getContentSnapshot(404L)).thenReturn(deletedQuestion);
        when(client.delete(any(DeleteRequest.class))).thenReturn(mock(DeleteResponse.class));

        answerService.upsertByAnswerId(201L);
        answerService.upsertByAnswerId(202L);
        answerService.upsertByAnswerId(203L);
        answerService.upsertByAnswerId(204L);

        ArgumentCaptor<DeleteRequest> captor = ArgumentCaptor.forClass(DeleteRequest.class);
        verify(client, times(4)).delete(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(DeleteRequest::index, DeleteRequest::id)
                .containsExactly(
                        tuple("answer", "201"),
                        tuple("answer", "202"),
                        tuple("answer", "203"),
                        tuple("answer", "204")
                );
        verify(client, never()).index(any(IndexRequest.class));
    }

    @Test
    void bulkItemFailureRemainsVisibleToSearchReconcileRetry() throws Exception {
        when(contentQueryService.getContentFactSnapshots(List.of(9L)))
                .thenReturn(List.of(visibleContent(9L, LocalDateTime.of(2026, 9, 26, 11, 0))));
        BulkResponse response = mock(BulkResponse.class);
        BulkResponseItem item = mock(BulkResponseItem.class);
        ErrorCause error = mock(ErrorCause.class);
        when(response.errors()).thenReturn(true);
        when(response.items()).thenReturn(List.of(item));
        when(item.error()).thenReturn(error);
        when(item.index()).thenReturn("content");
        when(item.id()).thenReturn("9");
        when(error.reason()).thenReturn("mapping rejected");
        when(client.bulk(any(BulkRequest.class))).thenReturn(response);

        assertThatThrownBy(() -> contentService.upsertBatchByContentIds(List.of(9L)))
                .isInstanceOf(SearchFailedException.class)
                .hasMessageContaining("ES bulk")
                .hasMessageContaining("index=content")
                .hasMessageContaining("id=9")
                .hasMessageContaining("mapping rejected");
    }

    @Test
    void transportFailureWithBulkLikeTextIsWrappedByTypeInsteadOfMessagePrefix() throws Exception {
        when(contentQueryService.getContentFactSnapshots(List.of(10L)))
                .thenReturn(List.of(visibleContent(10L, LocalDateTime.of(2026, 9, 26, 11, 1))));
        RuntimeException transportFailure = new RuntimeException("ES bulk 同步失败：connection reset");
        when(client.bulk(any(BulkRequest.class))).thenThrow(transportFailure);

        assertThatThrownBy(() -> contentService.upsertBatchByContentIds(List.of(10L)))
                .isNotInstanceOf(SearchFailedException.class)
                .hasMessage("ES bulk 同步异常")
                .hasCause(transportFailure);
    }

    @Test
    @SuppressWarnings("unchecked")
    void contentSearchKeepsPagingFiltersHighlightAndScore() throws Exception {
        String storedEs7Time = "2026-09-26T02:30:00.000Z";
        Map<String, Object> source = Map.ofEntries(
                Map.entry("contentId", 42),
                Map.entry("contentType", 2),
                Map.entry("title", "ES8 迁移标题"),
                Map.entry("content", "ES8 迁移正文"),
                Map.entry("publishUserId", 7),
                Map.entry("auditStatus", 1),
                Map.entry("liked", 3),
                Map.entry("collectCount", 4),
                Map.entry("commentCount", 5),
                Map.entry("createTime", storedEs7Time),
                Map.entry("isDeleted", 0)
        );
        SearchResponse<Map> response = mock(SearchResponse.class);
        HitsMetadata<Map> hits = mock(HitsMetadata.class);
        TotalHits total = mock(TotalHits.class);
        Hit<Map> hit = mock(Hit.class);
        when(response.hits()).thenReturn(hits);
        when(hits.total()).thenReturn(total);
        when(total.value()).thenReturn(1L);
        when(hits.hits()).thenReturn(List.of(hit));
        when(hit.source()).thenReturn(source);
        when(hit.score()).thenReturn(2.5D);
        when(hit.highlight()).thenReturn(Map.of(
                "title", List.of("<em>ES8</em> 迁移标题"),
                "content", List.of("<em>ES8</em> 迁移正文")
        ));
        when(client.search(any(SearchRequest.class), eq(Map.class))).thenReturn(response);

        Page<ContentDocument> page = contentService.searchContent("ES8", 2, 2, 10);

        assertThat(page.getTotal()).isEqualTo(1L);
        assertThat(page.getPageNum()).isEqualTo(2);
        assertThat(page.getPageSize()).isEqualTo(10);
        assertThat(page).singleElement().satisfies(content -> {
            assertThat(content.getContentId()).isEqualTo(42L);
            assertThat(content.getTitle()).isEqualTo("<em>ES8</em> 迁移标题");
            assertThat(content.getContent()).isEqualTo("<em>ES8</em> 迁移正文");
            assertThat(content.getEsSearchScore()).isEqualTo(2.5D);
            assertThat(content.getCreateTime()).isEqualTo(
                    Instant.parse(storedEs7Time).atZone(ZoneId.systemDefault()).toLocalDateTime());
        });

        ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
        verify(client).search(captor.capture(), eq(Map.class));
        SearchRequest request = captor.getValue();
        assertThat(request.index()).containsExactly("content");
        assertThat(request.from()).isEqualTo(10);
        assertThat(request.size()).isEqualTo(10);
        assertThat(request.query().bool().must()).hasSize(1);
        assertThat(request.query().bool().filter()).hasSize(3);
        assertThat(request.sort()).hasSize(2);
        assertThat(request.highlight().fields()).containsKeys("title", "content");
    }

    @Test
    void answerSearchFailureKeepsExistingRagDegradation() throws Exception {
        when(client.search(any(SearchRequest.class), eq(Map.class)))
                .thenThrow(new IOException("es unavailable"));

        assertThat(answerService.searchAnswers("考研", 20)).isEmpty();
    }

    private ContentSnapshotVO visibleContent(Long contentId, LocalDateTime createdAt) {
        return ContentSnapshotVO.builder()
                .contentId(contentId)
                .contentType(2)
                .title("ES8 迁移标题")
                .content("ES8 迁移正文")
                .publishUserId(7L)
                .auditStatus(1)
                .likedCount(3)
                .collectCount(4)
                .commentCount(5)
                .createTime(createdAt)
                .isDeleted(0)
                .build();
    }

    private AnswerSnapshotVO visibleAnswer(Long answerId, Long questionId) {
        return AnswerSnapshotVO.builder()
                .answerId(answerId)
                .questionId(questionId)
                .userId(8L)
                .content("ES8 迁移回答")
                .likeCount(2)
                .commentCount(1)
                .isAccepted(0)
                .auditStatus(1)
                .isDeleted(0)
                .createTime(LocalDateTime.of(2026, 9, 26, 10, 30))
                .build();
    }
}
