package com.quanta.demo0.search.es.mapper;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.Result;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.DeleteRequest;
import co.elastic.clients.elasticsearch.core.DeleteResponse;
import co.elastic.clients.elasticsearch.core.IndexRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import com.quanta.demo0.search.es.document.ContentDocument;
import com.quanta.demo0.search.es.query.ElasticsearchQueryFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 搜索域内容文档 Mapper。
 *
 * <p>只负责 content 索引的 ES 文档读写和序列化，不查询 MySQL，也不承载内容可见性规则。</p>
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ContentDocumentMapper {

    private static final String INDEX_NAME = "content";

    private final ElasticsearchClient client;
    private final ElasticsearchQueryFactory queryFactory;

    /**
     * 写入或覆盖一个内容文档。
     */
    public void index(ContentDocument document) throws IOException {
        IndexRequest<Map<String, Object>> request = IndexRequest.of(builder -> builder
                .index(INDEX_NAME)
                .id(String.valueOf(document.getContentId()))
                .document(toSource(document)));
        client.index(request);
    }

    /**
     * 批量写入或删除内容文档。
     */
    public BulkResponse bulk(List<ContentDocument> upsertDocuments, List<Long> deleteIds) throws IOException {
        BulkRequest.Builder builder = new BulkRequest.Builder();
        for (ContentDocument document : upsertDocuments) {
            builder.operations(operation -> operation.index(index -> index
                    .index(INDEX_NAME)
                    .id(String.valueOf(document.getContentId()))
                    .document(toSource(document))));
        }
        for (Long id : deleteIds) {
            builder.operations(operation -> operation.delete(delete -> delete
                    .index(INDEX_NAME)
                    .id(String.valueOf(id))));
        }
        return client.bulk(builder.build());
    }

    /**
     * 删除内容文档；ES 中不存在时按幂等成功处理。
     */
    public void delete(Long contentId) {
        try {
            DeleteRequest request = DeleteRequest.of(builder -> builder
                    .index(INDEX_NAME)
                    .id(String.valueOf(contentId)));
            DeleteResponse response = client.delete(request);
            if (response.result() == Result.NotFound) {
                log.debug("ES 内容文档不存在，视为幂等成功，id={}", contentId);
            }
        } catch (ElasticsearchException e) {
            if (e.status() == 404) {
                log.debug("ES 内容文档不存在，视为幂等成功，id={}", contentId);
                return;
            }
            throw new IllegalStateException("ES 删除内容失败，id=" + contentId, e);
        } catch (Exception e) {
            throw new IllegalStateException("ES 删除内容异常，id=" + contentId, e);
        }
    }

    /**
     * 执行内容索引查询。
     */
    @SuppressWarnings("rawtypes")
    public SearchResponse<Map> search(String keyword, Integer contentType, int from, int size) throws IOException {
        return client.search(queryFactory.contentSearch(keyword, contentType, from, size), Map.class);
    }

    private Map<String, Object> toSource(ContentDocument document) {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("contentId", document.getContentId());
        source.put("contentType", document.getContentType());
        source.put("title", document.getTitle());
        source.put("content", document.getContent());
        source.put("publishUserId", document.getPublishUserId());
        source.put("auditStatus", document.getAuditStatus());
        source.put("liked", document.getLiked());
        source.put("collectCount", document.getCollectCount());
        source.put("commentCount", document.getCommentCount());
        source.put("createTime", document.getCreateTime());
        source.put("isDeleted", document.getIsDeleted());
        return source;
    }
}
