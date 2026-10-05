package com.quanta.demo0.moderation.service.impl;

import com.quanta.demo0.moderation.dto.ModerationTargetQueryDTO;
import com.quanta.demo0.moderation.entity.ModerationRecord;
import com.quanta.demo0.content.exception.ContentFailedException;
import com.quanta.demo0.moderation.mapper.ModerationRecordMapper;
import com.quanta.demo0.moderation.service.AdminModerationService;
import com.quanta.demo0.moderation.vo.ModerationRecordVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理端审核记录查询服务实现类。
 *
 * 核心职责：
 * 1. 提供单目标与批量目标的“最新审核记录”查询能力；
 * 2. 统一参数校验、目标类型规范化与结果映射；
 * 3. 为后台审核详情页和列表页提供可直接渲染的数据结构。
 *
 * 设计说明：
 * - 批量接口设置单次查询上限，避免大批量请求冲击数据库；
 * - 批量请求按目标去重，减少重复查询与无效开销。
 *
 * ============================================================
 * 【为什么"查最新一条"就够了】
 * ============================================================
 * 审核记录表对 (target_type, target_id, provider) 有唯一键（uk_target_provider），
 * 每次机审都是 ON DUPLICATE KEY UPDATE 覆盖写入（见 ContentModerationServiceImpl.saveRecord），
 * 一个目标永远只有一条记录——selectLatest（XML 里 ORDER BY update_time DESC LIMIT 1）
 * 拿到的就是当前生效的机审结论，不存在"多条历史怎么取"的问题。
 * 【批量接口的取舍】逐条调用 selectLatest 而不是一条 IN 大查询：
 * 单次上限 50 条（BATCH_MAX）兜住循环次数，换来实现简单；
 * 超限直接抛 ContentFailedException，防止列表页一次拖全量记录。
 */
@Service
public class AdminModerationServiceImpl implements AdminModerationService {

    // 单次最多查询 50 条审核记录，避免数据库压力过大
    private static final int BATCH_MAX = 50;

    @Autowired
    private ModerationRecordMapper moderationRecordMapper;


    /**
     * 查询目标最新审核记录（用于审核详情页）
     * 1. 校验目标类型和目标ID是否合法-》2. 从数据库查询最新审核记录-》3. 转换为VO返回
     *
     * 【坑】targetType 先 trim 再转大写才去查——入库侧存的是枚举名
     * （CONTENT/ANSWER/COMMENT，见 ModerationTargetType），管理端传参大小写不可控，
     * 归一化必须发生在 SQL 之前。
     */
    @Override
    public ModerationRecordVO getLatest(String targetType, Long targetId) {
        validateTarget(targetType, targetId);
        ModerationRecord record = moderationRecordMapper.selectLatest(targetType.trim().toUpperCase(), targetId);
        return toVo(record);
    }


    /**
     * 批量查询目标最新审核记录（用于审核列表页）
     * 1. 校验查询参数是否合法-》2. 从数据库查询最新审核记录-》3. 转换为VO返回
     *
     * 【坑】非法条目（null/缺字段）静默跳过而不是抛错——批量接口里一只害虫
     * 不该弄死整批；查询无记录（toVo 返回 null）也不占 key，
     * 调用方以 Map 里"有没有这个 key"判断是否存在机审记录。
     */
    @Override
    public Map<String, ModerationRecordVO> batchLatest(List<ModerationTargetQueryDTO> queries) {
        Map<String, ModerationRecordVO> result = new HashMap<>();
        if (queries == null || queries.isEmpty()) {
            return result;
        }
        if (queries.size() > BATCH_MAX) {
            throw new ContentFailedException("单次最多查询 " + BATCH_MAX + " 条审核记录");
        }
        for (ModerationTargetQueryDTO query : queries) {
            if (query == null || !StringUtils.hasText(query.getTargetType()) || query.getTargetId() == null) {
                continue;
            }

            String type = query.getTargetType().trim().toUpperCase();
            Long id = query.getTargetId();
            String key = buildKey(type, id);
            if (result.containsKey(key)) {
                continue;
            }
            ModerationRecord record = moderationRecordMapper.selectLatest(type, id);
            ModerationRecordVO vo = toVo(record);
            if (vo != null) {
                result.put(key, vo);
            }
        }
        return result;
    }

    private void validateTarget(String targetType, Long targetId) {
        if (!StringUtils.hasText(targetType)) {
            throw new ContentFailedException("targetType 不能为空");
        }
        if (targetId == null || targetId <= 0) {
            throw new ContentFailedException("targetId 不合法");
        }
    }

    private String buildKey(String targetType, Long targetId) {
        return targetType + ":" + targetId;
    }

    /** 实体转展示 VO：面向管理端的最小字段集——contentFingerprint 与 rawResponse 不外露（后者可能很大，需要深查时再查库）。 */
    private ModerationRecordVO toVo(ModerationRecord record) {
        if (record == null) {
            return null;
        }
        return ModerationRecordVO.builder()
                .targetType(record.getTargetType())// 目标类型
                .targetId(record.getTargetId())// 业务 ID（帖子/回答/评论 ID）
                .provider(record.getProvider())// 审核服务商
                .decision(record.getDecision())// 审核决策
                .riskLevel(record.getRiskLevel())
                .labels(record.getLabels())
                .rejectReason(record.getRejectReason())
                .taskStatus(record.getTaskStatus())
                .retryCount(record.getRetryCount())
                .updateTime(record.getUpdateTime())
                .build();
    }
}
