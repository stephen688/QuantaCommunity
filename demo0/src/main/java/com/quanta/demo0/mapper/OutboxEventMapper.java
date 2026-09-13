package com.quanta.demo0.mapper;

import com.github.pagehelper.Page;
import com.quanta.demo0.dto.OutboxEventQueryDTO;
import com.quanta.demo0.entity.OutboxEvent;
import com.quanta.demo0.vo.EventRetryDistributionVO;
import com.quanta.demo0.vo.EventStatusCountVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface OutboxEventMapper {

    int insert(OutboxEvent event);

    OutboxEvent selectByEventId(
            @Param("eventId") String eventId
    );

    /**
     * 在事务中查找可以抢占的事件。
     *
     * FOR UPDATE SKIP LOCKED 的作用：
     * 实例 A 锁住一条数据后，
     * 实例 B 会跳过它，继续寻找其他数据。
     */
    List<OutboxEvent> selectClaimableForUpdate(
            @Param("now") LocalDateTime now,
            @Param("limit") int limit
    );

    /**
     * 把任务标记为 PROCESSING，并记录当前处理实例。
     */
    int claimForProcessing(
            @Param("id") Long id,
            @Param("lockedBy") String lockedBy,
            @Param("lockedUntil") LocalDateTime lockedUntil,
            @Param("now") LocalDateTime now
    );

    /**
     * 只有拥有当前租约的实例才能标记 SENT。
     */
    int markSentByOwner(
            @Param("id") Long id,
            @Param("lockedBy") String lockedBy,
            @Param("sentTime") LocalDateTime sentTime
    );

    /**
     * 发送失败，重新回到 PENDING。
     */
    int markRetryByOwner(
            @Param("id") Long id,
            @Param("lockedBy") String lockedBy,
            @Param("retryCount") int retryCount,
            @Param("nextRetryTime") LocalDateTime nextRetryTime,
            @Param("lastError") String lastError
    );

    /**
     * 重试耗尽，进入 DEAD。
     */
    int markDeadByOwner(
            @Param("id") Long id,
            @Param("lockedBy") String lockedBy,
            @Param("lastError") String lastError
    );


    /**
     * 管理端分页查询 Outbox。
     */
    Page<OutboxEvent> pageAdmin(@Param("query") OutboxEventQueryDTO query);

    /**
     * 只有 DEAD 状态才能人工重放。
     */
    int replayDead(@Param("eventId") String eventId, @Param("adminId") Long adminId, @Param("now") LocalDateTime now);

    /**
     * Inbox DEAD 重放时，让原 Outbox 重新发送相同 eventId。
     */
    int resetForInboxReplay(@Param("eventId") String eventId, @Param("adminId") Long adminId, @Param("now") LocalDateTime now);

    List<EventStatusCountVO> countByStatus();

    Double selectAverageSendLatencyMs();

    Long selectLongestBacklogSeconds();

    List<EventRetryDistributionVO> selectRetryDistribution();

    int deleteCompletedBefore(@Param("before") LocalDateTime before);
}
