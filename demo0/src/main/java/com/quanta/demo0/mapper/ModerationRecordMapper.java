// d:/download/资料/day01/后端初始工程/demo0/src/main/java/com/quanta/demo0/mapper/ModerationRecordMapper.java
package com.quanta.demo0.mapper;

import com.quanta.demo0.entity.ModerationRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ModerationRecordMapper {

    /**
     * 插入或更新审核记录
     * 基于唯一索引 (target_type, target_id, provider) 实现幂等写入
     */
    int insertOrUpdate(ModerationRecord record);

    /**
     * 查询目标最新审核记录
     */
    ModerationRecord selectLatest(@Param("targetType") String targetType, @Param("targetId") Long targetId);
}