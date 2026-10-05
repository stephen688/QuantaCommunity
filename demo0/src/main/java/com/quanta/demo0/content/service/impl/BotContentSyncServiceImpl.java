package com.quanta.demo0.content.service.impl;

import com.quanta.demo0.content.vo.BotSyncDocVO;
import com.quanta.demo0.content.vo.BotSyncPageVO;
import com.quanta.demo0.content.dto.BotPolicyDocDTO;
import com.quanta.demo0.content.entity.BotPolicyDoc;
import com.quanta.demo0.content.exception.ContentFailedException;
import com.quanta.demo0.content.mapper.BotContentSyncMapper;
import com.quanta.demo0.content.service.BotContentSyncService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;

/** 三源内容同步实现，使用 update_time 大于等于水位线保证宁重不漏。 */

/**
 * 教学补充（三源的 SQL 口径见 BotContentSyncMapper 的类注释，HTTP 契约见
 * BotContentController.sync 的说明，这里讲服务层自己扛的设计决策）：
 *
 * ============================================================
 * 【同步协议的容错根基：服务端"宁重不漏" × 消费端"幂等 upsert"】
 * ============================================================
 * 这两个设计互为前提，拆开看哪一半都站不住：
 *   - 服务端敢用 >= 水位线 + offset 分页（两处都会重复下发），是因为约定
 *     QuantaBot 按 docId 幂等 upsert —— **重一条只是白覆盖一次，
 *     漏一条却是 Bot 知识库静默缺失，没有任何机制能发现它**；
 *   - Bot 敢无脑覆盖，是因为服务端每次下发的都是该文档的完整最新快照
 *     （title/content/status 全量字段），而不是难以对账的"变更指令"。
 * 想做"精确一次"就得让服务端记住每个消费方的同步进度 —— 游标状态的持久化、
 * 消费方掉线/重置的处理，复杂度远超本场景收益。
 * **跨系统同步：简单可重复 > 精确但脆弱。**
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class BotContentSyncServiceImpl implements BotContentSyncService {

    private static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter DATE_ONLY = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final int MAX_PAGE_SIZE = 200;

    private final BotContentSyncMapper botContentSyncMapper;

    /**
     * 增量同步入口：归一化入参 → count 算总数 → 按页捞三源文档流。
     *
     * 【入参先夹紧再进 SQL】pageNum 拉回 ≥1，pageSize 夹在 1~MAX_PAGE_SIZE：
     * 上限是服务端自我保护 —— 契约上不能让调用方一个 pageSize=100000
     * 把三张表一口气捞走；Bot 正常翻页（默认 50）完全不受影响。
     * 【防线思想】对外接口的数值入参都要按"它会被传成 0、负数、10^9"来设计。
     *
     * 【hasMore = 页码 × 页大小 < total】total 来自 countSync，与 selectSyncBatch
     * 是两条独立 SQL，并发写入下可能轻微不一致 —— 宁重不漏的语义对这种误差
     * 不敏感（多拉到一页空的就停），不值得为它上锁或追求快照一致性。
     */
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

    /**
     * 政策文档 upsert：先按 docId 查，没有就 INSERT，有就 UPDATE
     * （UPDATE 顺带 is_deleted=0 —— 已软删的 docId 重新登记即"复活"）。
     *
     * 【为什么先查再改，而不是 INSERT ... ON DUPLICATE KEY】
     * doc_id 上没有唯一索引（selectByDocId 用 limit 1 兜底异常重复行），
     * ON DUPLICATE KEY 无从生效；先查后改还能区分"新增 / 更新复活"打进日志，运营可观测。
     *
     * 【坑：并发窗口】两个请求同时 upsert 同一 docId 可能都查不到、双双 INSERT。
     * 这是运营手动维护的低频接口，读取侧又有 limit 1 兜底，项目接受该窗口；
     * 要收紧的正确姿势是"加唯一索引 + 捕获重复键改走更新"，而不是上分布式锁。
     */
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

    /**
     * 软删政策文档（墓碑）：置 is_deleted=1 并刷新 update_time —— 后者是关键，
     * 删除要靠 update_time 的变化被下一轮水位线同步**作为事件传播**给 Bot
     * （删除也是数据，见 BotContentSyncMapper 类注释；物理删就传不出事件了）。
     * SQL 的 WHERE is_deleted=0 让重复调用影响 0 行（幂等）。
     */
    @Override
    public void softDeletePolicyDoc(String docId) {
        if (docId == null || docId.isBlank()) {
            throw new ContentFailedException("docId 不能为空");
        }
        botContentSyncMapper.softDeletePolicyDocByDocId(docId);
        log.info("政策文档软删（墓碑）：docId={}", docId);
    }

    /**
     * 解析水位线：先按完整时间戳解析，失败退回纯日期（取当天 00:00:00 起）。
     * 【为什么支持两种格式】完整时间戳给 Bot 程序记录水位线用；
     * 纯日期给人工运维用 —— "把某天以来的变更重推一遍"直接传日期即可，不必拼 00:00:00。
     * 两种都失败时，报错文案直接写明支持的格式，让调用方一眼知道怎么改。
     */
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

    /** 入参防御：非空 + 长度上限（docId≤64、title≤200）一次校验完，报错文案直接写明边界，省一次来回试探。 */
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
