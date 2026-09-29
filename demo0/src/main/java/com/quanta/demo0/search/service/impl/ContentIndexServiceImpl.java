package com.quanta.demo0.search.service.impl;

import com.github.pagehelper.Page;
import com.quanta.demo0.content.vo.ContentSnapshotVO;
import com.quanta.demo0.content.service.ContentQueryService;
import com.quanta.demo0.search.es.document.ContentDocument;
import com.quanta.demo0.search.es.mapper.ContentDocumentMapper;
import com.quanta.demo0.search.exception.SearchFailedException;
import com.quanta.demo0.search.result.ReindexResult;
import com.quanta.demo0.search.service.ContentIndexService;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import co.elastic.clients.elasticsearch.core.search.Hit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 搜索域内容索引服务实现。
 *
 * <p>MySQL 负责提供内容事实，ContentDocumentMapper 负责 ES 读写；审核和软删除状态在写入前
 * 决定 upsert 或幂等删除，查询结果仅转换为搜索域 ContentDocument。</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ContentIndexServiceImpl implements ContentIndexService {

    private static final int DEFAULT_BATCH_SIZE = 1000;
    private static final DateTimeFormatter SPACE_DATE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ContentQueryService contentQueryService;
    private final ContentDocumentMapper contentDocumentMapper;

    /**
     * 按 MySQL 当前可见性状态写入或删除 content 文档。
     */
    @Override
    public void upsertByContentId(Long contentId) {
        if (contentId == null) {
            log.warn("upsertByContentId 参数为空");
            return;
        }
        try {
            ContentSnapshotVO content = contentQueryService.getContentSnapshot(contentId);
            if (!isVisible(content)) {
                deleteDocumentByContentId(contentId);
                return;
            }
            contentDocumentMapper.index(toDocument(content));
            log.debug("upsertByContentId 成功，contentId={}", contentId);
        } catch (Exception e) {
            log.error("upsertByContentId 失败，contentId={}", contentId, e);
            throw new SearchFailedException("ES upsert 失败，contentId=" + contentId);
        }
    }

    /**
     * 批量按 MySQL 快照执行 content 索引 upsert/delete，并保留 bulk 失败明细。
     */
    @Override
    public void upsertBatchByContentIds(List<Long> contentIds) {
        if (contentIds == null || contentIds.isEmpty()) {
            throw new SearchFailedException("contentIds 不能为空");
        }
        List<Long> validIds = contentIds.stream()
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (validIds.isEmpty()) {
            throw new SearchFailedException("无有效 contentId");
        }

        List<ContentSnapshotVO> contents = contentQueryService.getContentFactSnapshots(validIds);
        if (contents == null) {
            contents = List.of();
        }
        List<Long> foundIds = contents.stream().map(ContentSnapshotVO::getContentId).toList();
        List<Long> deleteIds = validIds.stream()
                .filter(id -> !foundIds.contains(id))
                .collect(Collectors.toCollection(ArrayList::new));
        List<ContentDocument> upsertDocuments = new ArrayList<>();
        for (ContentSnapshotVO content : contents) {
            if (isVisible(content)) {
                upsertDocuments.add(toDocument(content));
            } else if (content != null) {
                deleteIds.add(content.getContentId());
            }
        }

        if (upsertDocuments.isEmpty() && deleteIds.isEmpty()) {
            log.info("upsertBatchByContentIds 无有效操作，contentIds={}", validIds);
            return;
        }

        try {
            BulkResponse response = contentDocumentMapper.bulk(upsertDocuments, deleteIds);
            if (response.errors()) {
                String failureMessage = bulkFailureMessage(response);
                log.error("ES bulk 失败：{}", failureMessage);
                throw new SearchFailedException("ES bulk 同步失败：" + failureMessage);
            }
            log.info("upsertBatchByContentIds 成功，upsert={}, delete={}",
                    upsertDocuments.size(), deleteIds.size());
        } catch (SearchFailedException e) {
            log.error("upsertBatchByContentIds 执行失败", e);
            throw e;
        } catch (Exception e) {
            log.error("upsertBatchByContentIds 执行异常", e);
            throw new RuntimeException("ES bulk 同步异常", e);
        }
    }

    /**
     * 删除 content 文档；空 ID 按旧服务语义忽略。
     */
    @Override
    public void deleteDocumentByContentId(Long contentId) {
        if (contentId == null) {
            log.warn("deleteDocumentByContentId 参数为空");
            return;
        }
        contentDocumentMapper.delete(contentId);
    }

    /**
     * 执行内容分页检索并把 ES source 转换为搜索域文档。
     */
    @Override
    @SuppressWarnings("rawtypes")
    public Page<ContentDocument> searchContent(String keyword, Integer contentType, Integer current, Integer pageSize) {
        if (StringUtils.isBlank(keyword)) {
            throw new IllegalArgumentException("搜索关键词不能为空");
        }
        int actualCurrent = current == null || current <= 0 ? 1 : current;
        int actualPageSize = pageSize == null || pageSize <= 0 ? 10 : pageSize;
        int maxPage = Math.max(1, 10000 / actualPageSize);
        if (actualCurrent > maxPage) {
            log.warn("搜索页码超过 ES 最大限制，current={}, maxPage={}", actualCurrent, maxPage);
            Page<ContentDocument> emptyPage = new Page<>(actualCurrent, actualPageSize);
            emptyPage.setTotal(0);
            return emptyPage;
        }

        try {
            int from = (actualCurrent - 1) * actualPageSize;
            var response = contentDocumentMapper.search(keyword, contentType, from, actualPageSize);
            long total = response.hits().total() == null ? 0 : response.hits().total().value();
            List<ContentDocument> documents = new ArrayList<>();
            for (Hit<Map> hit : response.hits().hits()) {
                Map<String, Object> source = hit.source();
                if (source == null) {
                    continue;
                }
                ContentDocument document = fromSource(source);
                document.setTitle(firstHighlight(hit.highlight(), "title", document.getTitle()));
                document.setContent(firstHighlight(hit.highlight(), "content", document.getContent()));
                if (hit.score() != null) {
                    document.setEsSearchScore(hit.score());
                }
                documents.add(document);
            }
            Page<ContentDocument> page = new Page<>(actualCurrent, actualPageSize);
            page.setTotal(total);
            page.addAll(documents);
            return page;
        } catch (Exception e) {
            log.error("ES 搜索失败，keyword={}", keyword, e);
            throw new RuntimeException("ES 搜索失败", e);
        }
    }

    /**
     * 按分页从 MySQL 重建 content 索引，单批失败时保留已统计结果并终止后续批次。
     */
    @Override
    public ReindexResult reindexAllFromMySql(int batchSize) {
        int actualBatchSize = batchSize;
        if (actualBatchSize <= 0) {
            actualBatchSize = DEFAULT_BATCH_SIZE;
            log.warn("batchSize 不合法，使用默认值 {}", DEFAULT_BATCH_SIZE);
        }

        int total = 0;
        int success = 0;
        int failure = 0;
        boolean completed = true;
        int offset = 0;
        while (true) {
            List<ContentSnapshotVO> contents = null;
            try {
                contents = contentQueryService.getContentSnapshotsForReindex(offset, actualBatchSize);
                if (contents == null || contents.isEmpty()) {
                    break;
                }
                List<ContentDocument> upsertDocuments = new ArrayList<>();
                List<Long> deleteIds = new ArrayList<>();
                for (ContentSnapshotVO content : contents) {
                    if (isVisible(content)) {
                        upsertDocuments.add(toDocument(content));
                    } else if (content != null) {
                        deleteIds.add(content.getContentId());
                    }
                }

                if (!upsertDocuments.isEmpty() || !deleteIds.isEmpty()) {
                    BulkResponse response = contentDocumentMapper.bulk(upsertDocuments, deleteIds);
                    int batchTotal = upsertDocuments.size() + deleteIds.size();
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
                offset += actualBatchSize;
                if (contents.size() < actualBatchSize) {
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

    private boolean isVisible(ContentSnapshotVO content) {
        return content != null && content.getIsDeleted() == 0 && content.getAuditStatus() == 1;
    }

    private ContentDocument toDocument(ContentSnapshotVO content) {
        return ContentDocument.builder()
                .contentId(content.getContentId())
                .contentType(content.getContentType())
                .title(content.getTitle())
                .content(content.getContent())
                .publishUserId(content.getPublishUserId())
                .auditStatus(content.getAuditStatus())
                .liked(content.getLikedCount())
                .collectCount(content.getCollectCount())
                .commentCount(content.getCommentCount())
                .createTime(content.getCreateTime())
                .isDeleted(content.getIsDeleted())
                .build();
    }

    private ContentDocument fromSource(Map<String, Object> source) {
        return ContentDocument.builder()
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

    private String firstHighlight(Map<String, List<String>> highlights, String field, String fallback) {
        if (highlights == null) {
            return fallback;
        }
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
        return "index=" + item.index()
                + ", id=" + item.id()
                + ", reason=" + (reason == null ? "未知 ES bulk 错误" : reason);
    }
}
