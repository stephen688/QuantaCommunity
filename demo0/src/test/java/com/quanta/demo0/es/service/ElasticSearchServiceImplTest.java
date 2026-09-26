package com.quanta.demo0.es.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ErrorCause;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.IndexRequest;
import co.elastic.clients.elasticsearch.core.IndexResponse;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.core.search.HitsMetadata;
import co.elastic.clients.elasticsearch.core.search.TotalHits;
import com.github.pagehelper.Page;
import com.quanta.demo0.entity.Content;
import com.quanta.demo0.mapper.ContentMapper;
import com.quanta.demo0.mapper.QuestionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ES 服务核心契约测试。
 *
 * 边界：只锁定迁移时最容易改变的索引文档、查询语义、bulk 失败传播和 RAG 降级；
 * 不重复测试 Java Client 自身的 builder/getter 行为。
 */
@ExtendWith(MockitoExtension.class)
class ElasticSearchServiceImplTest {

    @Mock
    private ElasticsearchClient client;

    @Mock
    private ContentMapper contentMapper;

    @Mock
    private QuestionMapper questionMapper;

    private ElasticSearchServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ElasticSearchServiceImpl();
        assertThatCode(() -> ReflectionTestUtils.setField(service, "client", client))
                .as("ES 服务必须注入 Elasticsearch 8 Java API Client")
                .doesNotThrowAnyException();
        ReflectionTestUtils.setField(service, "contentMapper", contentMapper);
        ReflectionTestUtils.setField(service, "questionMapper", questionMapper);
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void visibleContentKeepsExistingIndexAndDocumentShape() throws Exception {
        LocalDateTime createdAt = LocalDateTime.of(2026, 9, 26, 10, 30);
        Content content = visibleContent(42L, createdAt);
        when(contentMapper.selectById(42L)).thenReturn(content);
        when(client.index(any(IndexRequest.class))).thenReturn(mock(IndexResponse.class));

        service.upsertByContentId(42L);

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
    void bulkItemFailureRemainsVisibleToSearchReconcileRetry() throws Exception {
        when(contentMapper.selectBatchIds(List.of(9L)))
                .thenReturn(List.of(visibleContent(9L, LocalDateTime.of(2026, 9, 26, 11, 0))));
        BulkResponse response = mock(BulkResponse.class);
        BulkResponseItem item = mock(BulkResponseItem.class);
        ErrorCause error = mock(ErrorCause.class);
        when(response.errors()).thenReturn(true);
        when(response.items()).thenReturn(List.of(item));
        when(item.error()).thenReturn(error);
        when(error.reason()).thenReturn("mapping rejected");
        when(client.bulk(any(BulkRequest.class))).thenReturn(response);

        assertThatThrownBy(() -> service.upsertBatchByContentIds(List.of(9L)))
                .hasMessageContaining("ES bulk")
                .hasMessageContaining("mapping rejected");
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

        Page<Content> page = service.searchContent("ES8", 2, 2, 10);

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

        assertThat(service.searchAnswers("考研", 20)).isEmpty();
    }

    private Content visibleContent(Long contentId, LocalDateTime createdAt) {
        return Content.builder()
                .contentId(contentId)
                .contentType(2)
                .title("ES8 迁移标题")
                .content("ES8 迁移正文")
                .publishUserId(7L)
                .auditStatus(1)
                .liked(3)
                .collectCount(4)
                .commentCount(5)
                .createTime(createdAt)
                .isDeleted(0)
                .build();
    }
}
