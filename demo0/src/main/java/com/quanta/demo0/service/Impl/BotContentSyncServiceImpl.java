package com.quanta.demo0.service.Impl;

import com.quanta.demo0.controller.bot.vo.BotSyncDocVO;
import com.quanta.demo0.controller.bot.vo.BotSyncPageVO;
import com.quanta.demo0.dto.BotPolicyDocDTO;
import com.quanta.demo0.entity.BotPolicyDoc;
import com.quanta.demo0.exception.ContentFailedException;
import com.quanta.demo0.mapper.BotContentSyncMapper;
import com.quanta.demo0.service.BotContentSyncService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;

/** 三源内容同步实现，使用 update_time 大于等于水位线保证宁重不漏。 */
@Service
@Slf4j
@RequiredArgsConstructor
public class BotContentSyncServiceImpl implements BotContentSyncService {

    private static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter DATE_ONLY = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final int MAX_PAGE_SIZE = 200;

    private final BotContentSyncMapper botContentSyncMapper;

    @Override
    public BotSyncPageVO getSync(String since, int pageNum, int pageSize) {
        LocalDateTime sinceAt = parseSince(since);
        int normalizedPage = Math.max(pageNum, 1);
        int normalizedSize = Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE);
        long total = botContentSyncMapper.countSync(sinceAt);
        List<BotSyncDocVO> items = botContentSyncMapper.selectSyncBatch(
                sinceAt, (normalizedPage - 1) * normalizedSize, normalizedSize);
        return BotSyncPageVO.builder()
                .items(items)
                .hasMore((long) normalizedPage * normalizedSize < total)
                .build();
    }

    @Override
    public void upsertPolicyDoc(BotPolicyDocDTO dto) {
        validatePolicyDoc(dto);
        BotPolicyDoc existing = botContentSyncMapper.selectByDocId(dto.getDocId());
        BotPolicyDoc document = BotPolicyDoc.builder()
                .docId(dto.getDocId())
                .title(dto.getTitle())
                .content(dto.getContent())
                .build();
        if (existing == null) {
            botContentSyncMapper.insertPolicyDoc(document);
        } else {
            botContentSyncMapper.updatePolicyDocByDocId(document);
        }
        log.info("政策文档 upsert：docId={}（{}）", dto.getDocId(),
                existing == null ? "新增" : "更新/复活");
    }

    @Override
    public void softDeletePolicyDoc(String docId) {
        if (docId == null || docId.isBlank()) {
            throw new ContentFailedException("docId 不能为空");
        }
        botContentSyncMapper.softDeletePolicyDocByDocId(docId);
        log.info("政策文档软删（墓碑）：docId={}", docId);
    }

    private LocalDateTime parseSince(String since) {
        if (since == null || since.isBlank()) {
            throw new ContentFailedException("since 不能为空");
        }
        try {
            return LocalDateTime.parse(since, DATE_TIME);
        } catch (DateTimeParseException dateTimeError) {
            try {
                return LocalDate.parse(since, DATE_ONLY).atStartOfDay();
            } catch (DateTimeParseException dateError) {
                throw new ContentFailedException(
                        "since 格式非法（支持 yyyy-MM-dd 或 yyyy-MM-dd HH:mm:ss）");
            }
        }
    }

    private void validatePolicyDoc(BotPolicyDocDTO dto) {
        if (dto == null
                || dto.getDocId() == null || dto.getDocId().isBlank() || dto.getDocId().length() > 64
                || dto.getTitle() == null || dto.getTitle().isBlank() || dto.getTitle().length() > 200
                || dto.getContent() == null || dto.getContent().isBlank()) {
            throw new ContentFailedException(
                    "政策文档参数非法（docId≤64 字符、title≤200 字符、content 必填）");
        }
    }
}
