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
     */
    @Override
    public void upsertByAnswerId(Long answerId) {
        if (answerId == null) {
            log.warn("upsertByAnswerId 参数为空");
            return;
        }
        try {
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
     */
    @Override
    @SuppressWarnings("rawtypes")
    public List<AnswerDocument> searchAnswers(String keyword, int topK) {
        if (StringUtils.isBlank(keyword)) {
            return new ArrayList<>();
        }
        try {
            var response = answerDocumentMapper.search(keyword, topK);
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

    private boolean isVisible(ContentSnapshotVO content) {
        return content != null && content.getIsDeleted() == 0 && content.getAuditStatus() == 1;
    }

    private boolean isVisible(AnswerSnapshotVO answer) {
        return answer != null && answer.getIsDeleted() == 0 && answer.getAuditStatus() == 1;
    }

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
