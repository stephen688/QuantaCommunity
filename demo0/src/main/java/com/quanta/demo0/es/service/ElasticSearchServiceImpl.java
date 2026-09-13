package com.quanta.demo0.es.service;

import com.github.pagehelper.Page;
import com.quanta.demo0.entity.Content;
import com.quanta.demo0.entity.QuestionAnswer;
import com.quanta.demo0.es.document.AnswerDocument;
import com.quanta.demo0.es.document.ContentDocument;

import com.quanta.demo0.exception.SearchFailedException;
import com.quanta.demo0.mapper.QuestionMapper;
import com.quanta.demo0.result.ReindexResult;
import com.quanta.demo0.mapper.ContentMapper;

import com.quanta.demo0.vo.ContentVO;
import com.quanta.demo0.vo.PageVO;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.elasticsearch.action.bulk.BulkItemResponse;
import org.elasticsearch.action.bulk.BulkRequest;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.delete.DeleteRequest;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.MultiMatchQueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.SearchHits;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.elasticsearch.search.fetch.subphase.highlight.HighlightBuilder;
import org.elasticsearch.search.fetch.subphase.highlight.HighlightField;
import org.elasticsearch.search.sort.SortOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Elasticsearch 服务实现类
 */
@Service
@Slf4j
public class ElasticSearchServiceImpl implements ElasticSearchService {

    private static final String INDEX_NAME = "content";
    private static final String ANSWER_INDEX_NAME = "answer";
    private static final int DEFAULT_BATCH_SIZE = 1000;

    @Autowired
    private RestHighLevelClient client;

    @Autowired
    private ContentMapper contentMapper;

    @Autowired
    private QuestionMapper questionMapper;

    /**
     * 根据 contentId 执行 upsert（新增或更新）
     *步骤：
     * 1. 从数据库查询内容(看是否已删除或不存在)-> 2. 转换为 ES 文档对象->
     * 3. 发出 ES IndexRequest（自动新增或覆盖）->4. 执行请求
     *
     *
     * @param contentId
     */
    @Override
    public void upsertByContentId(Long contentId) {
        if (contentId == null) {
            log.warn("upsertByContentId 参数为空");
            return;
        }

        try {
            // 复用现有 selectById（带 is_deleted=0 过滤）
            Content content = contentMapper.selectById(contentId);

            // 查不到（已删除或不存在）→ 删除 ES 文档
            if (content == null || content.getIsDeleted() == 1 || content.getAuditStatus() != 1) {
                deleteDocumentByContentId(contentId);
                return;
            }

            // 有效内容 → upsert
            //转换为 ES 文档对象
            ContentDocument document = convertToDocument(content);
            //发出 ES IndexRequest（自动新增或覆盖）
            IndexRequest request = new IndexRequest(INDEX_NAME)
                    .id(String.valueOf(contentId))
                    .source(XContentType.JSON,// 文档内容
                            "contentId", document.getContentId(),
                            "contentType", document.getContentType(),
                            "title", document.getTitle(),
                            "content", document.getContent(),
                            "publishUserId", document.getPublishUserId(),
                            "auditStatus", document.getAuditStatus(),
                            "liked", document.getLiked(),
                            "collectCount", document.getCollectCount(),
                            "commentCount", document.getCommentCount(),
                            "createTime", document.getCreateTime(),
                            "isDeleted", document.getIsDeleted());

            //执行请求
            client.index(request, RequestOptions.DEFAULT);
            log.debug("upsertByContentId 成功，contentId={}", contentId);

        } catch (Exception e) {
            log.error("upsertByContentId 失败，contentId={}", contentId, e);
            throw new SearchFailedException("ES upsert 失败，contentId=" + contentId);
        }
    }

    /**
     * 根据 contentIds 执行批量 upsert（新增或更新）
     *
     * @param contentIds
     */
    @Override
    public void upsertBatchByContentIds(List<Long> contentIds) {
        // 1. 校验入参非空
        if (contentIds == null || contentIds.isEmpty()) {
            throw new SearchFailedException("contentIds 不能为空");
        }

        // 2. 过滤非法 ID（null）并去重
        List<Long> validIds = contentIds.stream()
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());

        if (validIds.isEmpty()) {
            throw new SearchFailedException("无有效 contentId");
        }

        // 3. 批量查 MySQL
        List<Content> contents = contentMapper.selectBatchIds(validIds);
        List<Long> foundIds = contents.stream()
                .map(Content::getContentId)
                .toList();

        // 4. 计算"请求里有但 MySQL 查不到"的 ID（需要从 ES 删除）
        List<Long> deleteIds = validIds.stream()
                .filter(id -> !foundIds.contains(id))
                .collect(Collectors.toList());

        // 5. 遍历查到的内容，分组
        List<ContentDocument> upsertDocs = new ArrayList<>();

        for (Content content : contents) {
            if (content.getIsDeleted() == 0 && content.getAuditStatus() == 1) {
                // 有效内容 → 转换为 ES 文档对象并加入 upsert 列表
                upsertDocs.add(convertToDocument(content));
            } else {
                // 无效内容 → 加入 deleteId 列表
                deleteIds.add(content.getContentId());
            }
        }

        // 6. 执行一次 ES bulk，将 upsert 和 delete 操作打包发送
        if (upsertDocs.isEmpty() && deleteIds.isEmpty()) {
            log.info("upsertBatchByContentIds 无有效操作，contentIds={}", validIds);
            return;
        }

        try {

            BulkRequest bulkRequest = new BulkRequest();

            // 8. 构建 upsert 请求
            for (ContentDocument doc : upsertDocs) {
                IndexRequest request = new IndexRequest(INDEX_NAME)
                        .id(String.valueOf(doc.getContentId()))
                        .source(XContentType.JSON,
                                "contentId", doc.getContentId(),
                                "contentType", doc.getContentType(),
                                "title", doc.getTitle(),
                                "content", doc.getContent(),
                                "publishUserId", doc.getPublishUserId(),
                                "auditStatus", doc.getAuditStatus(),
                                "liked", doc.getLiked(),
                                "collectCount", doc.getCollectCount(),
                                "commentCount", doc.getCommentCount(),
                                "createTime", doc.getCreateTime(),
                                "isDeleted", doc.getIsDeleted());
                bulkRequest.add(request);
            }

            // 7. 构建删除请求
            for (Long id : deleteIds) {
                DeleteRequest request = new DeleteRequest(INDEX_NAME, String.valueOf(id));
                bulkRequest.add(request);
            }

            BulkResponse bulkResponse = client.bulk(bulkRequest, RequestOptions.DEFAULT);

            // 7. 若 bulk 有失败项，直接判定本次失败并抛异常
            if (bulkResponse.hasFailures()) {
                String failureMessage = bulkResponse.buildFailureMessage();
                log.error("ES bulk 失败：{}", failureMessage);
                throw new RuntimeException("ES bulk 同步失败：" + failureMessage);
            }

            log.info("upsertBatchByContentIds 成功，upsert={}, delete={}", upsertDocs.size(), deleteIds.size());

        } catch (Exception e) {
            log.error("upsertBatchByContentIds 执行异常", e);
            throw new RuntimeException("ES bulk 同步异常", e);
        }
    }

    /**
     * 根据 contentId 从 ES 索引删除文档（或按你的策略改成写 isDeleted=1）
     * 步骤：1. 校验参数非空-》2. 构建删除请求-》3. 执行删除操作
     *
     * @param contentId
     */
    @Override
    public void deleteDocumentByContentId(Long contentId) {
        if (contentId == null) {
            log.warn("deleteDocumentByContentId 参数为空");
            return;
        }

        try {
            DeleteRequest request = new DeleteRequest(INDEX_NAME, String.valueOf(contentId));
            client.delete(request, RequestOptions.DEFAULT);
            log.debug("deleteDocumentByContentId 成功，contentId={}", contentId);

        } catch (org.elasticsearch.ElasticsearchStatusException e) {
            // ES 文档不存在时按"幂等成功"处理
            if (e.status().getStatus() == 404) {
                log.debug("ES 文档不存在，视为幂等成功，contentId={}", contentId);
                return;
            }
            log.error("deleteDocumentByContentId 失败，contentId={}", contentId, e);
            throw new RuntimeException("ES 删除失败，contentId=" + contentId, e);

        } catch (Exception e) {
            log.error("deleteDocumentByContentId 异常，contentId={}", contentId, e);
            throw new RuntimeException("ES 删除异常，contentId=" + contentId, e);
        }
    }

    /**
     * 全量重建索引（先同步实现，后续可改异步任务）
     * 目的是将 MySQL 中的所有内容数据重新索引到 ES 中，
     * 确保 ES 索引与 MySQL 数据完全一致。
     *
     * @param batchSize 每批处理的记录数，建议根据实际情况调整（如 1000）
     * @return ReindexResult 包含总记录数、成功数、失败数和是否完成
     */
    @Override
    public ReindexResult reindexAllFromMySql(int batchSize) {
        // 1. 校验 batchSize
        if (batchSize <= 0) {
            batchSize = DEFAULT_BATCH_SIZE;// 使用默认的批量大小，为了避免一次拉取过多数据导致内存问题
            log.warn("batchSize 不合法，使用默认值 {}", DEFAULT_BATCH_SIZE);
        }

        int total = 0;
        int success = 0;
        int failure = 0;
        boolean completed = true;
        int offset = 0;// 从 MySQL 的第一条记录开始分页拉取

        while (true) {
            List<Content> contents = null;
            try {
                // 2. 分页批量拉取 MySQL（使用新增的无过滤方法）
                 contents = contentMapper.selectAllForReindex(offset, batchSize);
                if (contents == null || contents.isEmpty()) {
                    break;
                }

                // 3. 按"可索引条件"分成两类
                List<ContentDocument> upsertDocs = new ArrayList<>();
                List<Long> deleteIds = new ArrayList<>();

                for (Content content : contents) {
                    if (content.getIsDeleted() == 0 && content.getAuditStatus() == 1) {
                        upsertDocs.add(convertToDocument(content));
                    } else {
                        deleteIds.add(content.getContentId());
                    }
                }

                // 4. 执行 ES bulk
                if (!upsertDocs.isEmpty() || !deleteIds.isEmpty()) {
                    BulkRequest bulkRequest = new BulkRequest();

                    for (ContentDocument doc : upsertDocs) {
                        IndexRequest request = new IndexRequest(INDEX_NAME)
                                .id(String.valueOf(doc.getContentId()))
                                .source(XContentType.JSON,
                                        "contentId", doc.getContentId(),
                                        "contentType", doc.getContentType(),
                                        "title", doc.getTitle(),
                                        "content", doc.getContent(),
                                        "publishUserId", doc.getPublishUserId(),
                                        "auditStatus", doc.getAuditStatus(),
                                        "liked", doc.getLiked(),
                                        "collectCount", doc.getCollectCount(),
                                        "commentCount", doc.getCommentCount(),
                                        "createTime", doc.getCreateTime(),
                                        "isDeleted", doc.getIsDeleted());
                        bulkRequest.add(request);
                    }

                    for (Long id : deleteIds) {
                        DeleteRequest request = new DeleteRequest(INDEX_NAME, String.valueOf(id));
                        bulkRequest.add(request);
                    }

                    BulkResponse bulkResponse = client.bulk(bulkRequest, RequestOptions.DEFAULT);

                    // 5. 统计本批次的总记录数、成功数和失败数
                    int batchTotal = upsertDocs.size() + deleteIds.size();
                    // 累加总记录数,无论成功与否都算入总数
                    total += batchTotal;

                    // 如果有失败项，统计失败数和成功数，并记录日志
                    if (bulkResponse.hasFailures()) {
                        int batchFailure = 0;
                        for (BulkItemResponse item : bulkResponse.getItems()) {
                            if (item.isFailed()) {
                                batchFailure++;
                            }
                        }
                        // 计算本批次的成功数
                        int batchSuccess = batchTotal - batchFailure;
                        success += batchSuccess;
                        failure += batchFailure;
                        log.error("全量重建批次失败，offset={}, 失败数={}", offset, batchFailure);
                    } else {
                        // 本批次全部成功
                        success += batchTotal;
                    }
                }

                //
                offset += batchSize;

                if (contents.size() < batchSize) {
                    break;
                }

            } catch (Exception e) {
                // 任一批次异常：标记未完成，中断循环
                completed = false;

                // 失败计数用实际查到的数量（不是 batchSize）
                int batchTotal = (contents!= null) ? contents.size() : 0;
                failure += batchTotal;
                total += batchTotal;

                log.error("全量重建批次异常，offset={}, 该批失败数={}", offset, batchTotal, e);
                break;  // 直接中断，避免索引不一致
            }
        }

        return ReindexResult.builder()
                .total(total)
                .success(success)
                .failure(failure)
                .completed(completed)
                .build();
    }

    /**
     * 搜索内容（ES 查询）
     * 步骤：1. 参数校验与兜底-》2. 构建 ES 查询请求-》
     * 3. 执行查询-》4. 解析结果-》5. 返回分页结果
     */
    @Override
    public Page<Content> searchContent(String keyword, Integer contentType, Integer current, Integer pageSize) {
        // 1. 参数校验与兜底
        if (StringUtils.isBlank(keyword)) {
            throw new IllegalArgumentException("搜索关键词不能为空");
        }

        if (current == null || current <= 0) {
            current = 1;
        }

        if (pageSize == null || pageSize <= 0) {
            pageSize = 10;
        }

        // 限制最大页码，避免深分页
        int maxResultWindow = 10000;
        int maxPage = maxResultWindow / pageSize;
        if (maxPage<1){
            maxPage=1;
        }
        if (current > maxPage) {
            log.warn("搜索页码超过 ES 最大限制，current={}, maxPage={}", current, maxPage);
            // 返回空 Page
            Page<Content> emptyPage = new Page<>(current, pageSize);
            emptyPage.setTotal(0);
            return emptyPage;
        }

        try {
            // 2. 构建 ES 查询请求
            SearchRequest searchRequest = new SearchRequest(INDEX_NAME);
            SearchSourceBuilder sourceBuilder = new SearchSourceBuilder();

            // 3. 构建查询条件
            BoolQueryBuilder boolQuery = QueryBuilders.boolQuery();

            // 3.1 关键词匹配
            MultiMatchQueryBuilder multiMatchQuery = QueryBuilders
                    .multiMatchQuery(keyword, "title", "content")
                    .type(MultiMatchQueryBuilder.Type.BEST_FIELDS)// 最佳字段匹配
                    .fuzziness("AUTO");// 自动模糊匹配
            boolQuery.must(multiMatchQuery);

            // 3.2 内容类型过滤
            if (contentType != null) {
                boolQuery.filter(QueryBuilders.termQuery("contentType", contentType));
            }

            // 3.3 只查有效内容
            boolQuery.filter(QueryBuilders.termQuery("isDeleted", 0));
            boolQuery.filter(QueryBuilders.termQuery("auditStatus", 1));

            sourceBuilder.query(boolQuery);

            // 4. 分页
            int from = (current - 1) * pageSize;
            sourceBuilder.from(from);
            sourceBuilder.size(pageSize);

            // 5. 排序
            sourceBuilder.sort("_score", SortOrder.DESC);
            sourceBuilder.sort("createTime", SortOrder.DESC);

            // 6. 高亮
            HighlightBuilder highlightBuilder = new HighlightBuilder();
            highlightBuilder.field("title");
            highlightBuilder.field("content");
            highlightBuilder.preTags("<em>");
            highlightBuilder.postTags("</em>");
            sourceBuilder.highlighter(highlightBuilder);

            searchRequest.source(sourceBuilder);

            // 7. 执行查询
            SearchResponse searchResponse = client.search(searchRequest, RequestOptions.DEFAULT);

            // 8. 解析结果
            SearchHits hits = searchResponse.getHits();
            long total = 0;
            if (hits.getTotalHits()!=null) {
                 total = hits.getTotalHits().value;
            } else {
                log.warn("ES 搜索结果 totalHits 为空，keyword={}", keyword);
            }
            List<Content> contentList = new ArrayList<>();
            for (SearchHit hit : hits.getHits()) {
                Map<String, Object> sourceAsMap = hit.getSourceAsMap();

                Content content = new Content();

                // 9. 字段转换（安全类型转换）
                Object contentIdObj = sourceAsMap.get("contentId");
                if (contentIdObj != null) {
                    content.setContentId(((Number) contentIdObj).longValue());
                }

                Object contentTypeObj = sourceAsMap.get("contentType");
                if (contentTypeObj != null) {
                    content.setContentType(((Number) contentTypeObj).intValue());
                }

                // 处理高亮：优先使用高亮字段
                String title = (String) sourceAsMap.get("title");
                String contentText = (String) sourceAsMap.get("content");


                Map<String, HighlightField> highlightFields = hit.getHighlightFields();


                HighlightField titleHighlight = highlightFields.get("title");
                if (titleHighlight != null && titleHighlight.getFragments() != null && titleHighlight.getFragments().length > 0) {
                    title = titleHighlight.getFragments()[0].string();
                }

                HighlightField contentHighlight = highlightFields.get("content");
                if (contentHighlight != null && contentHighlight.getFragments() != null && contentHighlight.getFragments().length > 0) {
                    contentText = contentHighlight.getFragments()[0].string();
                }


                content.setTitle(title);
                content.setContent(contentText);

                Object publishUserIdObj = sourceAsMap.get("publishUserId");
                if (publishUserIdObj != null) {
                    content.setPublishUserId(((Number) publishUserIdObj).longValue());
                }

                Object auditStatusObj = sourceAsMap.get("auditStatus");
                if (auditStatusObj != null) {
                    content.setAuditStatus(((Number) auditStatusObj).intValue());
                }

                Object likedObj = sourceAsMap.get("liked");
                if (likedObj != null) {
                    content.setLiked(((Number) likedObj).intValue());
                }

                Object collectCountObj = sourceAsMap.get("collectCount");
                if (collectCountObj != null) {
                    content.setCollectCount(((Number) collectCountObj).intValue());
                }

                Object commentCountObj = sourceAsMap.get("commentCount");
                if (commentCountObj != null) {
                    content.setCommentCount(((Number) commentCountObj).intValue());
                }

                // createTime 转换
                Object createTimeObj = sourceAsMap.get("createTime");
                if (createTimeObj != null) {
                    // ES 返回的日期可能是字符串或 long
                    if (createTimeObj instanceof String) {
                        // 用 Instant 解析 UTC 时间，再转本地时区
                        String timeStr = (String) createTimeObj;
                        LocalDateTime createTime;
                        if (timeStr.endsWith("Z")) {
                            // 带时区的格式：2026-05-20T17:49:04.000Z
                            createTime = Instant.parse(timeStr).atZone(ZoneId.systemDefault()).toLocalDateTime();
                        } else {
                            // 不带时区的格式：2026-04-26T20:12:27
                            createTime = LocalDateTime.parse(timeStr);
                        }
                        content.setCreateTime(createTime);
                    }
                    // 如果是 long（epoch_millis），需要额外处理
                }
                content.setEsSearchScore((double) hit.getScore());
                contentList.add(content);


            }

            // 10. 组装 Page 结果（复用 PageHelper 结构）
            Page<Content> page = new Page<>(current, pageSize);
            page.setTotal(total);
            page.addAll(contentList);

            return page;

        } catch (Exception e) {
            log.error("ES 搜索失败，keyword={}", keyword, e);
            throw new RuntimeException("ES 搜索失败", e);
        }
    }





    /**
     * 将 MySQL Content 实体转换为 ES ContentDocument
     */
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



    /**
     * 根据 answerId 执行 upsert（新增或更新）
     */
    @Override
    public void upsertByAnswerId(Long answerId) {
        if (answerId == null) {
            log.warn("upsertByAnswerId 参数为空");
            return;
        }

        try {
            // 查询回答
            QuestionAnswer answer = questionMapper.selectById(answerId);

            // 查不到或状态无效 → 删除 ES 文档
            if (answer == null || answer.getIsDeleted() == 1 || answer.getAuditStatus() != 1) {
                deleteDocumentByAnswerId(answerId);
                return;
            }

            // 查询父帖（问题）标题
            Content question = contentMapper.selectById(answer.getQuestionId());
            // 父问题被删除或审核不通过时，回答即使自身通过审核也不能继续被搜索到。
            if (question == null || question.getIsDeleted() == 1 || question.getAuditStatus() != 1) {
                deleteDocumentByAnswerId(answerId);
                return;
            }
            String questionTitle = question.getTitle();

            // 构建 AnswerDocument
            AnswerDocument document = AnswerDocument.builder()
                    .answerId(answer.getAnswerId())
                    .questionId(answer.getQuestionId())
                    .questionTitle(questionTitle)
                    .answerContent(answer.getContent())
                    .userId(answer.getUserId())
                    .auditStatus(answer.getAuditStatus())
                    .likeCount(answer.getLikeCount())
                    .commentCount(answer.getCommentCount())
                    .isAccepted(answer.getIsAccepted())
                    .createTime(answer.getCreateTime())
                    .isDeleted(answer.getIsDeleted())
                    .build();

            // 写入 ES
            IndexRequest request = new IndexRequest(ANSWER_INDEX_NAME)
                    .id(String.valueOf(answerId))
                    .source(XContentType.JSON,
                            "answerId", document.getAnswerId(),
                            "questionId", document.getQuestionId(),
                            "questionTitle", document.getQuestionTitle(),
                            "answerContent", document.getAnswerContent(),
                            "userId", document.getUserId(),
                            "auditStatus", document.getAuditStatus(),
                            "likeCount", document.getLikeCount(),
                            "commentCount", document.getCommentCount(),
                            "isAccepted", document.getIsAccepted(),
                            "createTime", document.getCreateTime(),
                            "isDeleted", document.getIsDeleted());

            client.index(request, RequestOptions.DEFAULT);
            log.debug("upsertByAnswerId 成功，answerId={}", answerId);

        } catch (Exception e) {
            log.error("upsertByAnswerId 失败，answerId={}", answerId, e);
            throw new SearchFailedException("ES upsert 回答失败，answerId=" + answerId);
        }
    }

    /**
     * 根据 answerId 从 ES 索引删除回答文档
     */
    @Override
    public void deleteDocumentByAnswerId(Long answerId) {
        if (answerId == null) {
            log.warn("deleteDocumentByAnswerId 参数为空");
            return;
        }

        try {
            DeleteRequest request = new DeleteRequest(ANSWER_INDEX_NAME, String.valueOf(answerId));
            client.delete(request, RequestOptions.DEFAULT);
            log.debug("deleteDocumentByAnswerId 成功，answerId={}", answerId);

        } catch (org.elasticsearch.ElasticsearchStatusException e) {
            if (e.status().getStatus() == 404) {
                log.debug("ES 回答文档不存在，视为幂等成功，answerId={}", answerId);
                return;
            }
            log.error("deleteDocumentByAnswerId 失败，answerId={}", answerId, e);
            throw new RuntimeException("ES 删除回答失败，answerId=" + answerId, e);

        } catch (Exception e) {
            log.error("deleteDocumentByAnswerId 异常，answerId={}", answerId, e);
            throw new RuntimeException("ES 删除回答异常，answerId=" + answerId, e);
        }
    }

    /**
     * 搜索回答（ES 查询，供 RAG 检索使用）
     */
    @Override
    public List<AnswerDocument> searchAnswers(String keyword, int topK) {
        if (StringUtils.isBlank(keyword)) {
            return new ArrayList<>();
        }

        try {
            SearchRequest searchRequest = new SearchRequest(ANSWER_INDEX_NAME);
            SearchSourceBuilder sourceBuilder = new SearchSourceBuilder();

            // 关键词匹配
            MultiMatchQueryBuilder multiMatchQuery = QueryBuilders
                    .multiMatchQuery(keyword, "questionTitle", "answerContent")
                    .type(MultiMatchQueryBuilder.Type.BEST_FIELDS)
                    .fuzziness("AUTO");

            // 只查有效回答
            BoolQueryBuilder boolQuery = QueryBuilders.boolQuery();
            boolQuery.must(multiMatchQuery);
            boolQuery.filter(QueryBuilders.termQuery("isDeleted", 0));
            boolQuery.filter(QueryBuilders.termQuery("auditStatus", 1));

            sourceBuilder.query(boolQuery);
            sourceBuilder.size(topK);
            sourceBuilder.sort("_score", SortOrder.DESC);

            searchRequest.source(sourceBuilder);

            SearchResponse searchResponse = client.search(searchRequest, RequestOptions.DEFAULT);

            List<AnswerDocument> result = new ArrayList<>();
            for (SearchHit hit : searchResponse.getHits().getHits()) {
                Map<String, Object> sourceAsMap = hit.getSourceAsMap();

                AnswerDocument doc = new AnswerDocument();

                Object answerIdObj = sourceAsMap.get("answerId");
                if (answerIdObj != null) {
                    doc.setAnswerId(((Number) answerIdObj).longValue());
                }

                Object questionIdObj = sourceAsMap.get("questionId");
                if (questionIdObj != null) {
                    doc.setQuestionId(((Number) questionIdObj).longValue());
                }

                doc.setQuestionTitle((String) sourceAsMap.get("questionTitle"));
                doc.setAnswerContent((String) sourceAsMap.get("answerContent"));

                Object userIdObj = sourceAsMap.get("userId");
                if (userIdObj != null) {
                    doc.setUserId(((Number) userIdObj).longValue());
                }

                Object auditStatusObj = sourceAsMap.get("auditStatus");
                if (auditStatusObj != null) {
                    doc.setAuditStatus(((Number) auditStatusObj).intValue());
                }

                Object likeCountObj = sourceAsMap.get("likeCount");
                if (likeCountObj != null) {
                    doc.setLikeCount(((Number) likeCountObj).intValue());
                }

                Object commentCountObj = sourceAsMap.get("commentCount");
                if (commentCountObj != null) {
                    doc.setCommentCount(((Number) commentCountObj).intValue());
                }

                Object isAcceptedObj = sourceAsMap.get("isAccepted");
                if (isAcceptedObj != null) {
                    doc.setIsAccepted(((Number) isAcceptedObj).intValue());
                }

                Object createTimeObj = sourceAsMap.get("createTime");
                if (createTimeObj != null) {
                    // 用 Instant 解析 UTC 时间，再转本地时区
                    doc.setCreateTime(Instant.parse(createTimeObj.toString()).atZone(ZoneId.systemDefault()).toLocalDateTime());
                }

                Object isDeletedObj = sourceAsMap.get("isDeleted");
                if (isDeletedObj != null) {
                    doc.setIsDeleted(((Number) isDeletedObj).intValue());
                }
                // 提取 ES 检索得分
                doc.setEsSearchScore(Double.valueOf(hit.getScore()));
                result.add(doc);
            }

            return result;

        } catch (Exception e) {
            log.error("searchAnswers 失败，keyword={}", keyword, e);
            return new ArrayList<>();
        }
    }
}
