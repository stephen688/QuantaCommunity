package com.quanta.demo0.search.service.impl;

import co.elastic.clients.elasticsearch.core.search.Hit;
import com.quanta.demo0.answer.vo.AnswerSnapshotVO;
import com.quanta.demo0.content.vo.ContentSnapshotVO;
import com.quanta.demo0.content.service.ContentQueryService;
import com.quanta.demo0.answer.service.AnswerQueryService;
import com.quanta.demo0.search.es.document.AnswerDocument;
import com.quanta.demo0.search.es.mapper.AnswerDocumentMapper;
import com.quanta.demo0.search.exception.SearchFailedException;
import com.quanta.demo0.search.service.AnswerSearchService;
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

/**
 * 搜索域回答索引与召回服务实现。
 *
 * <p>回答和父问题的可见性由 MySQL 当前快照决定；ES 仅保存派生文档，检索失败继续保持 RAG
 * 的空结果降级语义。</p>
 *
 * ============================================================
 * 【同一份 ES 数据，为什么 upsert 抛异常、searchAnswers 却吞异常？】
 * ============================================================
 * upsertByAnswerId 失败会抛 SearchFailedException：写路径失败必须暴露，
 * 让上游（业务事务 / MQ 对账）感知并重试，否则 ES 会静默落后于 MySQL，
 * 出现"已被驳回的回答仍能搜到"这种口径漂移。
 * searchAnswers 失败则返回空列表：它是 RAG 检索（rag 包 EsRecallService）
 * 的回答召回源，属于"锦上添花"的旁路数据——ES 不可用时应降级为
 * "这一路没有召回"，而不是让整个问答链路跟着失败。
 * **写要响（暴露问题等重试），读要稳（降级不拖垮主流程）。**
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class AnswerSearchServiceImpl implements AnswerSearchService {

    private static final DateTimeFormatter SPACE_DATE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final AnswerQueryService answerQueryService;
    private final ContentQueryService contentQueryService;
    private final AnswerDocumentMapper answerDocumentMapper;

    /**
     * 按回答和父问题当前状态写入或删除 answer 文档。
     *
     * <p>【可见才写，不可见即删】回答本身满足 is_deleted=0 且 audit_status=1 还不够——
     * 父问题被软删或驳回时（isVisible(question) 不通过），回答也必须从 ES 移除，
     * 否则用户会搜到"挂在已删问题下的孤回答"。两条检查任一不过都走幂等删除。</p>
     */
    @Override
    public void upsertByAnswerId(Long answerId) {
        if (answerId == null) {
            log.warn("upsertByAnswerId 参数为空");
            return;
        }
        try {
            // 先看回答自己的 MySQL 快照（AnswerQueryService），再看父问题快照（ContentQueryService）。
            // 可见性以 MySQL 当前状态为唯一事实源，ES 文档只是派生品。
            AnswerSnapshotVO answer = answerQueryService.getAnswerSnapshot(answerId);
            if (!isVisible(answer)) {
                deleteDocumentByAnswerId(answerId);
                return;
            }
            ContentSnapshotVO question = contentQueryService.getContentSnapshot(answer.getQuestionId());
            if (!isVisible(question)) {
                deleteDocumentByAnswerId(answerId);
                return;
            }
            answerDocumentMapper.index(toDocument(answer, question));
            log.debug("upsertByAnswerId 成功，answerId={}", answerId);
        } catch (Exception e) {
            log.error("upsertByAnswerId 失败，answerId={}", answerId, e);
            throw new SearchFailedException("ES upsert 回答失败，answerId=" + answerId);
        }
    }

    /**
     * 删除 answer 文档；空 ID 按旧服务语义忽略。
     */
    @Override
    public void deleteDocumentByAnswerId(Long answerId) {
        if (answerId == null) {
            log.warn("deleteDocumentByAnswerId 参数为空");
            return;
        }
        answerDocumentMapper.delete(answerId);
    }

    /**
     * 执行回答召回；关键词为空或 ES 异常时返回空列表。
     *
     * <p>【召回不分页】size 直接等于 topK（由调用方传入，rag 包按 rag.es-top-k 配置决定），
     * 只取相关性最高的 topK 条，结果按 _score 降序。也不做高亮——
     * 对比内容搜索（ElasticsearchQueryFactory.contentSearch 带 &lt;em&gt; 高亮），
     * 这里的产出是给 RAG 喂料的原始文本，不是给前端渲染的。</p>
     */
    @Override
    @SuppressWarnings("rawtypes")
    public List<AnswerDocument> searchAnswers(String keyword, int topK) {
        if (StringUtils.isBlank(keyword)) {
            return new ArrayList<>();
        }
        try {
            var response = answerDocumentMapper.search(keyword, topK);
            // ES client 以 Map.class 读回 source（见 AnswerDocumentMapper.search），
            // 所以这里手工从 Map 逐字段收敛类型后拼 AnswerDocument，
            // 并把命中分回填到 esSearchScore 供 RAG 融合排序使用。
            List<AnswerDocument> result = new ArrayList<>();
            for (Hit<Map> hit : response.hits().hits()) {
                Map<String, Object> source = hit.source();
                if (source == null) {
                    continue;
                }
                AnswerDocument document = fromSource(source);
                document.setEsSearchScore(hit.score());
                result.add(document);
            }
            return result;
        } catch (Exception e) {
            log.error("searchAnswers 失败，keyword={}", keyword, e);
            return new ArrayList<>();
        }
    }

    /** 与 content 包一致的可见口径：未删除且审核通过。 */
    private boolean isVisible(ContentSnapshotVO content) {
        return content != null && content.getIsDeleted() == 0 && content.getAuditStatus() == 1;
    }

    /** 回答侧同口径：未删除且审核通过。 */
    private boolean isVisible(AnswerSnapshotVO answer) {
        return answer != null && answer.getIsDeleted() == 0 && answer.getAuditStatus() == 1;
    }

    /**
     * 写入侧组装 ES 文档：把回答正文与父问题标题冗余进同一份文档，
     * 这样 ES 的 multi_match 才能同时命中 questionTitle 和 answerContent 两个文本字段，
     * 召回时不需要二次 join MySQL。
     */
    private AnswerDocument toDocument(AnswerSnapshotVO answer, ContentSnapshotVO question) {
        return AnswerDocument.builder()
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
    }

    /**
     * 从 ES source Map 反序列化回答文档（与 toDocument 字段一一对应）。
     */
    private AnswerDocument fromSource(Map<String, Object> source) {
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

    /**
     * 兼容解析 createTime：ES 索引对该字段声明了四种 format
     * （"yyyy-MM-dd HH:mm:ss" / ISO 本地时间 / ISO+Z / epoch 毫秒，见
     * ElasticsearchIndexInitializer 的 mapping），按命中形态逐一尝试。
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
}
