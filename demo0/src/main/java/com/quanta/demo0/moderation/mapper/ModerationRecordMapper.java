// d:/download/资料/day01/后端初始工程/demo0/src/main/java/com/quanta/demo0/mapper/ModerationRecordMapper.java
package com.quanta.demo0.moderation.mapper;

import com.quanta.demo0.moderation.entity.ModerationRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * AI 审核记录表 tb_moderation_record 的 Mapper。
 * SQL 全部在 resources/mapper/moderation/ModerationRecordMapper.xml。
 */
@Mapper
public interface ModerationRecordMapper {

    /**
     * 插入或更新审核记录
     * 基于唯一索引 (target_type, target_id, provider) 实现幂等写入
     *
     * 【细节】XML 用 INSERT ... ON DUPLICATE KEY UPDATE 实现"覆盖最新结论"：
     * 冲突时只更新 decision/risk_level/labels/reject_reason/raw_response/
     * content_fingerprint/task_status/retry_count 和 update_time，
     * 唯一键三列与 create_time 保持首次写入值。
     */
    int insertOrUpdate(ModerationRecord record);

    /**
     * 查询目标最新审核记录
     *
     * 【实现】ORDER BY update_time DESC LIMIT 1——唯一索引含 provider，
     * 同一 target 理论上可能存在多行（不同 provider 各一行），这里只取最近更新的那条。
     */
    ModerationRecord selectLatest(@Param("targetType") String targetType, @Param("targetId") Long targetId);
}