package com.quanta.demo0.search.es.mapper;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.Result;
import co.elastic.clients.elasticsearch.core.DeleteRequest;
import co.elastic.clients.elasticsearch.core.DeleteResponse;
import co.elastic.clients.elasticsearch.core.IndexRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import com.quanta.demo0.search.es.document.AnswerDocument;
import com.quanta.demo0.search.es.query.ElasticsearchQueryFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 搜索域回答文档 Mapper。
 *
 * <p>只负责 answer 索引的 ES 文档读写和序列化，不读取回答或问题的 MySQL 实体。</p>
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class AnswerDocumentMapper {

    private static final String INDEX_NAME = "answer";

    private final ElasticsearchClient client;
    private final ElasticsearchQueryFactory queryFactory;

    /**
     * 写入或覆盖一个回答文档。
     */
    public void index(AnswerDocument document) throws IOException {
        IndexRequest<Map<String, Object>> request = IndexRequest.of(builder -> builder
                .index(INDEX_NAME)
                .id(String.valueOf(document.getAnswerId()))
                .document(toSource(document)));
        client.index(request);
    }

    /**
     * 删除回答文档；ES 中不存在时按幂等成功处理。
     */
    public void delete(Long answerId) {
        try {
            DeleteRequest request = DeleteRequest.of(builder -> builder
                    .index(INDEX_NAME)
                    .id(String.valueOf(answerId)));
            DeleteResponse response = client.delete(request);
            if (response.result() == Result.NotFound) {
                log.debug("ES 回答文档不存在，视为幂等成功，id={}", answerId);
            }
        } catch (ElasticsearchException e) {
            if (e.status() == 404) {
                log.debug("ES 回答文档不存在，视为幂等成功，id={}", answerId);
                return;
            }
            throw new IllegalStateException("ES 删除回答失败，id=" + answerId, e);
        } catch (Exception e) {
            throw new IllegalStateException("ES 删除回答异常，id=" + answerId, e);
        }
    }

    /**
     * 执行回答召回查询。
     */
    @SuppressWarnings("rawtypes")
    public SearchResponse<Map> search(String keyword, int topK) throws IOException {
        return client.search(queryFactory.answerSearch(keyword, topK), Map.class);
    }

    private Map<String, Object> toSource(AnswerDocument document) {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("answerId", document.getAnswerId());
        source.put("questionId", document.getQuestionId());
        source.put("questionTitle", document.getQuestionTitle());
        source.put("answerContent", document.getAnswerContent());
        source.put("userId", document.getUserId());
        source.put("auditStatus", document.getAuditStatus());
        source.put("likeCount", document.getLikeCount());
        source.put("commentCount", document.getCommentCount());
        source.put("isAccepted", document.getIsAccepted());
        source.put("createTime", document.getCreateTime());
        source.put("isDeleted", document.getIsDeleted());
        return source;
    }
}
