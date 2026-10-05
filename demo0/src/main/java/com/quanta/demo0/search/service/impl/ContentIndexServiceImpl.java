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
 *
 * ============================================================
 * 【同步链路全景：本类在"MySQL → ES"里的位置】
 * ============================================================
 * 内容的发布/审核/删除（ContentCommandServiceImpl、ContentAuditServiceImpl、
 * AdminContentServiceImpl）不直接改 ES，而是在业务事务里经 SearchEventProducer
 * 写一条 ES 校准事件到 Outbox（platform.mq），由 Outbox 派发到 RabbitMQ，
 * SearchReconcileConsumer（Inbox 去重）消费后经 SearchReconcileService 调到本类。
 * **本类不感知 MQ**，只提供"按 MySQL 现状对齐 ES"的原子动作 —— 对账、手工
 * 修数据、全量重建复用的是同一套方法。
 *
 * ============================================================
 * 【为什么每次都重查 MySQL，而不是相信事件里带的旧状态？】
 * ============================================================
 * 事件只携带 targetId，到达时 MySQL 状态可能又变了（审核通过后又被驳回、
 * 删除后又恢复不了但消息迟到）。**最终状态永远以 MySQL 当前事实为准**：
 * upsert 前先查快照，不可见（isDeleted!=0 或 auditStatus!=1，与 content 包
 * is_deleted=0 AND audit_status=1 的可见口径一致）就发删除 —— 调用方永远
 * 只有一个入口，不需要区分"该写还是该删"。
 *
 * ============================================================
 * 【三个写入口 + 一个读入口怎么分工？】
 * ============================================================
 * upsertByContentId 服务事件对账（单条）；upsertBatchByContentIds 服务批量
 * 修复（一次 IN 查询防 N+1）；reindexAllFromMySql 服务全量重建（offset/limit
 * 分页扫全表）。searchContent 是用户搜索的读入口。批量与重建里的删除都走
 * bulk 混合请求，单条删除才走 deleteDocumentByContentId。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ContentIndexServiceImpl implements ContentIndexService {

    // 全量重建每批从 MySQL 捞的行数，也是一次 bulk 携带的最大文档数。
    private static final int DEFAULT_BATCH_SIZE = 1000;
    // ES 返回的 createTime 可能是 "yyyy-MM-dd HH:mm:ss" 字符串形态，见 parseCreateTime。
    private static final DateTimeFormatter SPACE_DATE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ContentQueryService contentQueryService;
    private final ContentDocumentMapper contentDocumentMapper;

    /**
     * 按 MySQL 当前可见性状态写入或删除 content 文档。
     *
     * 【单条对账入口】先查事实快照（getContentSnapshot 直查 DB 不走缓存，
     * 对账必须用当前事实），可见 → 覆盖写，不可见 → 删文档。
     * 【坑】异常统一包成 SearchFailedException 抛出而不是吞掉 —— 让 MQ
     * 消费侧重试/进死信（重试逻辑在 SearchReconcileConsumer）。
     */
    @Override
    public void upsertByContentId(Long contentId) {
        if (contentId == null) {
            log.warn("upsertByContentId 参数为空");
            return;
        }
        try {
            // 对账用"事实版"快照：直查 DB、不做可见性过滤（见
            // ContentQueryServiceImpl.getContentSnapshot / getContentFactSnapshots 注释）。
            ContentSnapshotVO content = contentQueryService.getContentSnapshot(contentId);
            if (!isVisible(content)) {
                // 【要点】"驳回/软删/查无此行"不需要单独的删除接口，
                // 统一收敛到"先查事实，不可见就删"。
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
     *
     * 【与逐条循环的区别】一次 getContentFactSnapshots（IN 查询）+ 一次 bulk
     * 混合请求搞定全部，防 N+1；单条失败明细直接拼进异常消息，便于人工修数。
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
        // Fact 版快照可能少于请求数（行已被物理删除）—— 没查到的 id
        // 说明 MySQL 已不存在，ES 里若还有残留文档必须清掉。
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
            // bulk 不会因单条失败而抛错：失败藏在 items 里，必须显式检查 errors()。
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
     *
     * 【深分页边界】ES 默认 max_result_window=10000，from+size 超限直接报错；
     * 这里按 10000/pageSize 折算最大页码，超页返回空 Page 而不是抛 500。
     * 【回填三件事】高亮片段替换 title/content（只取第一个片段，无命中保留
     * 原文）、esSearchScore 回填相关性得分（RAG 融合排序用）。
     */
    @Override
    @SuppressWarnings("rawtypes")
    public Page<ContentDocument> searchContent(String keyword, Integer contentType, Integer current, Integer pageSize) {
        if (StringUtils.isBlank(keyword)) {
            throw new IllegalArgumentException("搜索关键词不能为空");
        }
        int actualCurrent = current == null || current <= 0 ? 1 : current;
        int actualPageSize = pageSize == null || pageSize <= 0 ? 10 : pageSize;
        // ES 的窗口上限是 from+size 总和，所以最大页码 = 10000 / pageSize 向下取整。
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
                // 相关性得分只在检索时存在（ES 不落盘），RAG 用它做双路融合。
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
     *
     * ============================================================
     * 【全量重建的"宁重不漏"与幂等】
     * ============================================================
     * MySQL 侧用 selectAllForReindex 扫**全表**（刻意不过滤 is_deleted/
     * audit_status，见 ContentMapper.selectAllForReindex 注释）：可见行 upsert、
     * 不可见行发 delete —— 只有扫全集才能把"曾建过索引、后来不可见"的
     * 文档清干净，宁可多写一遍也不能漏删。
     * **重建可以放心重跑**：ES 文档 _id=contentId，重复写入是覆盖；删除对
     * 不存在的文档幂等成功。所以中途失败（completed=false）后直接再调一次
     * 即可，已成功的批次重跑只是多做一遍同样的覆盖写。
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
                // 全表口径：已删/未审的行也返回，由下面的 isVisible 分流成
                // upsert 或 delete（可见性判断不在 SQL 里做）。
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
                    // bulk 逐条带结果：失败的只计入 failure，不拖累本批已成功的。
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
                // 最后一页（不足一批）说明扫完了，不用再多查一次空页。
                if (contents.size() < actualBatchSize) {
                    break;
                }
            } catch (Exception e) {
                // 【中止语义】某批异常就整批计入 failure 并停止：
                // 已成功的批次不回滚（覆盖写本就幂等），
                // completed=false 提示调用方"统计不完整，可整体重跑"。
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

    /**
     * 可见口径：未软删且审核通过 —— 与 content 包的查询口径
     * （is_deleted=0 AND audit_status=1，见 ContentMapper.xml）保持一致，
     * 单方面改动会造成 ES 与 MySQL 口径漂移。
     */
    private boolean isVisible(ContentSnapshotVO content) {
        return content != null && content.getIsDeleted() == 0 && content.getAuditStatus() == 1;
    }

    /**
     * ContentSnapshotVO → ContentDocument：字段一一对应，likedCount 改名 liked，
     * tags/updateTime 不进 ES（分工见 ContentDocument 类注释）。
     */
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

    /**
     * ES _source（Map）→ ContentDocument 的手动反序列化：search 声明为
     * Map.class，字段名必须与 toSource 写入的 key 一致（新增字段三处同步）。
     */
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

    /**
     * 解析 ES 返回的 createTime：ES 内部把 date 存成 epoch 毫秒（Number 形态，
     * mapping 里声明的 epoch_millis），也可能是命中格式化模板的字符串
     * （带空格的 "yyyy-MM-dd HH:mm:ss"、ISO 带 Z 后缀、ISO 本地格式），
     * 逐形态解析，谁命中走谁。
     */
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

    /**
     * 取字段高亮的第一个片段；该字段没有命中高亮时保留原文兜底。
     */
    private String firstHighlight(Map<String, List<String>> highlights, String field, String fallback) {
        if (highlights == null) {
            return fallback;
        }
        List<String> fragments = highlights.get(field);
        return fragments == null || fragments.isEmpty() ? fallback : fragments.get(0);
    }

    /**
     * 把 bulk 里所有失败条目拼成一行可读消息（index/id/reason），
     * 直接进异常文本与 error 日志，人工排查不用再翻 response。
     */
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
