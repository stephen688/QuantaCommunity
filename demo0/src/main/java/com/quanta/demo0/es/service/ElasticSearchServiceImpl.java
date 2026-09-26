package com.quanta.demo0.es.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.Result;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch._types.query_dsl.TextQueryType;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.DeleteRequest;
import co.elastic.clients.elasticsearch.core.DeleteResponse;
import co.elastic.clients.elasticsearch.core.IndexRequest;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.github.pagehelper.Page;
import com.quanta.demo0.entity.Content;
import com.quanta.demo0.entity.QuestionAnswer;
import com.quanta.demo0.es.document.AnswerDocument;
import com.quanta.demo0.es.document.ContentDocument;
import com.quanta.demo0.exception.SearchFailedException;
import com.quanta.demo0.mapper.ContentMapper;
import com.quanta.demo0.mapper.QuestionMapper;
import com.quanta.demo0.result.ReindexResult;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Elasticsearch 服务实现类。
 */
@Service
@Slf4j
public class ElasticSearchServiceImpl implements ElasticSearchService {

    private static final String INDEX_NAME = "content";
    private static final String ANSWER_INDEX_NAME = "answer";
    private static final int DEFAULT_BATCH_SIZE = 1000;
    private static final DateTimeFormatter SPACE_DATE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Autowired
    private ElasticsearchClient client;

    @Autowired
    private ContentMapper contentMapper;

    @Autowired
    private QuestionMapper questionMapper;

    @Override
    public void upsertByContentId(Long contentId) {
        if (contentId == null) {
            log.warn("upsertByContentId 参数为空");
            return;
        }
        try {
            Content content = contentMapper.selectById(contentId);
            if (content == null || content.getIsDeleted() == 1 || content.getAuditStatus() != 1) {
                deleteDocumentByContentId(contentId);
                return;
            }
            ContentDocument document = convertToDocument(content);
            IndexRequest<Map<String, Object>> request = IndexRequest.of(builder -> builder
                    .index(INDEX_NAME)
                    .id(String.valueOf(contentId))
                    .document(contentSource(document)));
            client.index(request);
            log.debug("upsertByContentId 成功，contentId={}", contentId);
        } catch (Exception e) {
            log.error("upsertByContentId 失败，contentId={}", contentId, e);
            throw new SearchFailedException("ES upsert 失败，contentId=" + contentId);
        }
    }

    @Override
    public void upsertBatchByContentIds(List<Long> contentIds) {
        if (contentIds == null || contentIds.isEmpty()) {
            throw new SearchFailedException("contentIds 不能为空");
        }
        List<Long> validIds = contentIds.stream()
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
        if (validIds.isEmpty()) {
            throw new SearchFailedException("无有效 contentId");
        }

        List<Content> contents = contentMapper.selectBatchIds(validIds);
        List<Long> foundIds = contents.stream().map(Content::getContentId).toList();
        List<Long> deleteIds = validIds.stream()
                .filter(id -> !foundIds.contains(id))
                .collect(Collectors.toList());
        List<ContentDocument> upsertDocs = new ArrayList<>();
        for (Content content : contents) {
            if (content.getIsDeleted() == 0 && content.getAuditStatus() == 1) {
                upsertDocs.add(convertToDocument(content));
            } else {
                deleteIds.add(content.getContentId());
            }
        }

        if (upsertDocs.isEmpty() && deleteIds.isEmpty()) {
            log.info("upsertBatchByContentIds 无有效操作，contentIds={}", validIds);
            return;
        }

        try {
            BulkResponse response = client.bulk(buildContentBulkRequest(upsertDocs, deleteIds));
            if (response.errors()) {
                String failureMessage = bulkFailureMessage(response);
                log.error("ES bulk 失败：{}", failureMessage);
                throw new RuntimeException("ES bulk 同步失败：" + failureMessage);
            }
            log.info("upsertBatchByContentIds 成功，upsert={}, delete={}", upsertDocs.size(), deleteIds.size());
        } catch (Exception e) {
            log.error("upsertBatchByContentIds 执行异常", e);
            if (e instanceof RuntimeException runtimeException
                    && runtimeException.getMessage() != null
                    && runtimeException.getMessage().startsWith("ES bulk 同步失败")) {
                throw runtimeException;
            }
            throw new RuntimeException("ES bulk 同步异常", e);
        }
    }

    @Override
    public void deleteDocumentByContentId(Long contentId) {
        if (contentId == null) {
            log.warn("deleteDocumentByContentId 参数为空");
            return;
        }
        deleteDocument(INDEX_NAME, contentId, "内容");
    }

    @Override
    public ReindexResult reindexAllFromMySql(int batchSize) {
        if (batchSize <= 0) {
            batchSize = DEFAULT_BATCH_SIZE;
            log.warn("batchSize 不合法，使用默认值 {}", DEFAULT_BATCH_SIZE);
        }

        int total = 0;
        int success = 0;
        int failure = 0;
        boolean completed = true;
        int offset = 0;
        while (true) {
            List<Content> contents = null;
            try {
                contents = contentMapper.selectAllForReindex(offset, batchSize);
                if (contents == null || contents.isEmpty()) {
                    break;
                }
                List<ContentDocument> upsertDocs = new ArrayList<>();
                List<Long> deleteIds = new ArrayList<>();
                for (Content content : contents) {
                    if (content.getIsDeleted() == 0 && content.getAuditStatus() == 1) {
                        upsertDocs.add(convertToDocument(content));
                    } else {
                        deleteIds.add(content.getContentId());
                    }
                }

                if (!upsertDocs.isEmpty() || !deleteIds.isEmpty()) {
                    BulkResponse response = client.bulk(buildContentBulkRequest(upsertDocs, deleteIds));
                    int batchTotal = upsertDocs.size() + deleteIds.size();
                    total += batchTotal;
                    if (response.errors()) {
                        int batchFailure = (int) response.items().stream()
                                .filter(item -> item.error() != null)
                                .count();
                        success += batchTotal - batchFailure;
                        failure += batchFailure;
                        log.error("全量重建批次失败，offset={}, 失败数={}，详情={}",
                                offset, batchFailure, bulkFailureMessage(response));
                    } else {
                        success += batchTotal;
                    }
                }
                offset += batchSize;
                if (contents.size() < batchSize) {
                    break;
                }
            } catch (Exception e) {
                completed = false;
                int batchTotal = contents != null ? contents.size() : 0;
                failure += batchTotal;
                total += batchTotal;
                log.error("全量重建批次异常，offset={}, 该批失败数={}", offset, batchTotal, e);
                break;
            }
        }

        return ReindexResult.builder()
                .total(total)
                .success(success)
                .failure(failure)
                .completed(completed)
                .build();
    }

    @Override
    public Page<Content> searchContent(String keyword, Integer contentType, Integer current, Integer pageSize) {
        if (StringUtils.isBlank(keyword)) {
            throw new IllegalArgumentException("搜索关键词不能为空");
        }
        if (current == null || current <= 0) {
            current = 1;
        }
        if (pageSize == null || pageSize <= 0) {
            pageSize = 10;
        }

        int maxPage = Math.max(1, 10000 / pageSize);
        if (current > maxPage) {
            log.warn("搜索页码超过 ES 最大限制，current={}, maxPage={}", current, maxPage);
            Page<Content> emptyPage = new Page<>(current, pageSize);
            emptyPage.setTotal(0);
            return emptyPage;
        }

        try {
            int from = (current - 1) * pageSize;
            SearchResponse<Map> response = client.search(
                    contentSearchRequest(keyword, contentType, from, pageSize), Map.class);
            long total = response.hits().total() == null ? 0 : response.hits().total().value();
            List<Content> contents = new ArrayList<>();
            for (Hit<Map> hit : response.hits().hits()) {
                Map<String, Object> source = hit.source();
                if (source == null) {
                    continue;
                }
                Content content = contentFromSource(source);
                content.setTitle(firstHighlight(hit.highlight(), "title", content.getTitle()));
                content.setContent(firstHighlight(hit.highlight(), "content", content.getContent()));
                if (hit.score() != null) {
                    content.setEsSearchScore(hit.score());
                }
                contents.add(content);
            }

            Page<Content> page = new Page<>(current, pageSize);
            page.setTotal(total);
            page.addAll(contents);
            return page;
        } catch (Exception e) {
            log.error("ES 搜索失败，keyword={}", keyword, e);
            throw new RuntimeException("ES 搜索失败", e);
        }
    }

    @Override
    public void upsertByAnswerId(Long answerId) {
        if (answerId == null) {
            log.warn("upsertByAnswerId 参数为空");
            return;
        }
        try {
            QuestionAnswer answer = questionMapper.selectById(answerId);
            if (answer == null || answer.getIsDeleted() == 1 || answer.getAuditStatus() != 1) {
                deleteDocumentByAnswerId(answerId);
                return;
            }
            Content question = contentMapper.selectById(answer.getQuestionId());
            if (question == null || question.getIsDeleted() == 1 || question.getAuditStatus() != 1) {
                deleteDocumentByAnswerId(answerId);
                return;
            }

            AnswerDocument document = AnswerDocument.builder()
                    .answerId(answer.getAnswerId())
                    .questionId(answer.getQuestionId())
                    .questionTitle(question.getTitle())
                    .answerContent(answer.getContent())
                    .userId(answer.getUserId())
                    .auditStatus(answer.getAuditStatus())
                    .likeCount(answer.getLikeCount())
                    .commentCount(answer.getCommentCount())
                    .isAccepted(answer.getIsAccepted())
                    .createTime(answer.getCreateTime())
                    .isDeleted(answer.getIsDeleted())
                    .build();
            IndexRequest<Map<String, Object>> request = IndexRequest.of(builder -> builder
                    .index(ANSWER_INDEX_NAME)
                    .id(String.valueOf(answerId))
                    .document(answerSource(document)));
            client.index(request);
            log.debug("upsertByAnswerId 成功，answerId={}", answerId);
        } catch (Exception e) {
            log.error("upsertByAnswerId 失败，answerId={}", answerId, e);
            throw new SearchFailedException("ES upsert 回答失败，answerId=" + answerId);
        }
    }

    @Override
    public void deleteDocumentByAnswerId(Long answerId) {
        if (answerId == null) {
            log.warn("deleteDocumentByAnswerId 参数为空");
            return;
        }
        deleteDocument(ANSWER_INDEX_NAME, answerId, "回答");
    }

    @Override
    public List<AnswerDocument> searchAnswers(String keyword, int topK) {
        if (StringUtils.isBlank(keyword)) {
            return new ArrayList<>();
        }
        try {
            SearchResponse<Map> response = client.search(
                    answerSearchRequest(keyword, topK), Map.class);
            List<AnswerDocument> result = new ArrayList<>();
            for (Hit<Map> hit : response.hits().hits()) {
                Map<String, Object> source = hit.source();
                if (source == null) {
                    continue;
                }
                AnswerDocument document = answerFromSource(source);
                document.setEsSearchScore(hit.score());
                result.add(document);
            }
            return result;
        } catch (Exception e) {
            log.error("searchAnswers 失败，keyword={}", keyword, e);
            return new ArrayList<>();
        }
    }

    private BulkRequest buildContentBulkRequest(List<ContentDocument> upsertDocs, List<Long> deleteIds) {
        BulkRequest.Builder builder = new BulkRequest.Builder();
        for (ContentDocument document : upsertDocs) {
            builder.operations(operation -> operation.index(index -> index
                    .index(INDEX_NAME)
                    .id(String.valueOf(document.getContentId()))
                    .document(contentSource(document))));
        }
        for (Long id : deleteIds) {
            builder.operations(operation -> operation.delete(delete -> delete
                    .index(INDEX_NAME)
                    .id(String.valueOf(id))));
        }
        return builder.build();
    }

    private SearchRequest contentSearchRequest(String keyword, Integer contentType, int from, int size) {
        BoolQuery.Builder boolQuery = new BoolQuery.Builder()
                .must(multiMatch(keyword, "title", "content"))
                .filter(termQuery("isDeleted", 0))
                .filter(termQuery("auditStatus", 1));
        if (contentType != null) {
            boolQuery.filter(termQuery("contentType", contentType));
        }
        return SearchRequest.of(builder -> builder
                .index(INDEX_NAME)
                .query(boolQuery.build()._toQuery())
                .from(from)
                .size(size)
                .sort(sort -> sort.field(field -> field.field("_score").order(SortOrder.Desc)))
                .sort(sort -> sort.field(field -> field.field("createTime").order(SortOrder.Desc)))
                .highlight(highlight -> highlight
                        .preTags("<em>")
                        .postTags("</em>")
                        .fields("title", field -> field)
                        .fields("content", field -> field)));
    }

    private SearchRequest answerSearchRequest(String keyword, int topK) {
        BoolQuery boolQuery = new BoolQuery.Builder()
                .must(multiMatch(keyword, "questionTitle", "answerContent"))
                .filter(termQuery("isDeleted", 0))
                .filter(termQuery("auditStatus", 1))
                .build();
        return SearchRequest.of(builder -> builder
                .index(ANSWER_INDEX_NAME)
                .query(boolQuery._toQuery())
                .size(topK)
                .sort(sort -> sort.field(field -> field.field("_score").order(SortOrder.Desc))));
    }

    private Query multiMatch(String keyword, String... fields) {
        return Query.of(query -> query.multiMatch(multiMatch -> multiMatch
                .query(keyword)
                .fields(List.of(fields))
                .type(TextQueryType.BestFields)
                .fuzziness("AUTO")));
    }

    private Query termQuery(String field, int value) {
        return Query.of(query -> query.term(term -> term.field(field).value(value)));
    }

    private void deleteDocument(String index, Long id, String documentName) {
        try {
            DeleteRequest request = DeleteRequest.of(builder -> builder
                    .index(index)
                    .id(String.valueOf(id)));
            DeleteResponse response = client.delete(request);
            if (response.result() == Result.NotFound) {
                log.debug("ES {}文档不存在，视为幂等成功，id={}", documentName, id);
                return;
            }
            log.debug("deleteDocument 成功，index={}, id={}", index, id);
        } catch (ElasticsearchException e) {
            if (e.status() == 404) {
                log.debug("ES {}文档不存在，视为幂等成功，id={}", documentName, id);
                return;
            }
            log.error("ES 删除{}失败，id={}", documentName, id, e);
            throw new RuntimeException("ES 删除" + documentName + "失败，id=" + id, e);
        } catch (Exception e) {
            log.error("ES 删除{}异常，id={}", documentName, id, e);
            throw new RuntimeException("ES 删除" + documentName + "异常，id=" + id, e);
        }
    }

    private ContentDocument convertToDocument(Content content) {
        return ContentDocument.builder()
                .contentId(content.getContentId())
                .contentType(content.getContentType())
                .title(content.getTitle())
                .content(content.getContent())
                .publishUserId(content.getPublishUserId())
                .auditStatus(content.getAuditStatus())
                .liked(content.getLiked())
                .collectCount(content.getCollectCount())
                .commentCount(content.getCommentCount())
                .createTime(content.getCreateTime())
                .isDeleted(content.getIsDeleted())
                .build();
    }

    private Content contentFromSource(Map<String, Object> source) {
        return Content.builder()
                .contentId(longValue(source.get("contentId")))
                .contentType(integerValue(source.get("contentType")))
                .title(stringValue(source.get("title")))
                .content(stringValue(source.get("content")))
                .publishUserId(longValue(source.get("publishUserId")))
                .auditStatus(integerValue(source.get("auditStatus")))
                .liked(integerValue(source.get("liked")))
                .collectCount(integerValue(source.get("collectCount")))
                .commentCount(integerValue(source.get("commentCount")))
                .createTime(parseCreateTime(source.get("createTime")))
                .isDeleted(integerValue(source.get("isDeleted")))
                .build();
    }

    private AnswerDocument answerFromSource(Map<String, Object> source) {
        return AnswerDocument.builder()
                .answerId(longValue(source.get("answerId")))
                .questionId(longValue(source.get("questionId")))
                .questionTitle(stringValue(source.get("questionTitle")))
                .answerContent(stringValue(source.get("answerContent")))
                .userId(longValue(source.get("userId")))
                .auditStatus(integerValue(source.get("auditStatus")))
                .likeCount(integerValue(source.get("likeCount")))
                .commentCount(integerValue(source.get("commentCount")))
                .isAccepted(integerValue(source.get("isAccepted")))
                .createTime(parseCreateTime(source.get("createTime")))
                .isDeleted(integerValue(source.get("isDeleted")))
                .build();
    }

    private Long longValue(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    private Integer integerValue(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    private String stringValue(Object value) {
        return value == null ? null : value.toString();
    }

    private LocalDateTime parseCreateTime(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return Instant.ofEpochMilli(number.longValue())
                    .atZone(ZoneId.systemDefault())
                    .toLocalDateTime();
        }

        String text = value.toString();
        if (text.endsWith("Z")) {
            return Instant.parse(text).atZone(ZoneId.systemDefault()).toLocalDateTime();
        }
        if (text.indexOf(' ') > 0) {
            return LocalDateTime.parse(text, SPACE_DATE_TIME_FORMATTER);
        }
        return LocalDateTime.parse(text);
    }

    private Map<String, Object> contentSource(ContentDocument document) {
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

    private Map<String, Object> answerSource(AnswerDocument document) {
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

    private String firstHighlight(Map<String, List<String>> highlights, String field, String fallback) {
        List<String> fragments = highlights.get(field);
        return fragments == null || fragments.isEmpty() ? fallback : fragments.get(0);
    }

    private String bulkFailureMessage(BulkResponse response) {
        return response.items().stream()
                .filter(item -> item.error() != null)
                .map(this::bulkItemFailureMessage)
                .collect(Collectors.joining("; "));
    }

    private String bulkItemFailureMessage(BulkResponseItem item) {
        String reason = item.error().reason();
        return reason == null ? "未知 ES bulk 错误" : reason;
    }
}
