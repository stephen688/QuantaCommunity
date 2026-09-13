package com.quanta.demo0.service.Impl;

import com.quanta.demo0.dto.ModerationTargetQueryDTO;
import com.quanta.demo0.entity.ModerationRecord;
import com.quanta.demo0.exception.ContentFailedException;
import com.quanta.demo0.mapper.ModerationRecordMapper;
import com.quanta.demo0.service.AdminModerationService;
import com.quanta.demo0.vo.ModerationRecordVO;
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
