package com.quanta.demo0.mapper;

import com.github.pagehelper.Page;
import com.quanta.demo0.dto.InboxEventQueryDTO;
import com.quanta.demo0.entity.InboxEvent;
import com.quanta.demo0.vo.EventStatusCountVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface InboxEventMapper {

    /**
     * 第一次收到消息时插入 Inbox。
     *
     * 如果 consumerName + eventId 已经存在，
     * 则不会重复插入。
     */
    int insertIfAbsent(InboxEvent inboxEvent);

    /**
     * 查询某个消费者对某个事件的处理记录。
     */
    InboxEvent selectByConsumerAndEvent(
            @Param("consumerName") String consumerName,
            @Param("eventId") String eventId
    );

    /**
     * 抢占租约已经过期的 Inbox。
     */
    int claimExpired(
            @Param("consumerName") String consumerName,
            @Param("eventId") String eventId,
            @Param("lockedBy") String lockedBy,
            @Param("lockedUntil") LocalDateTime lockedUntil,
            @Param("now") LocalDateTime now
    );

    /**
     * 处理成功。
     */
    int markSuccessByOwner(
            @Param("consumerName") String consumerName,
            @Param("eventId") String eventId,
            @Param("lockedBy") String lockedBy,
            @Param("processedTime") LocalDateTime processedTime
    );

    /**
     * 处理失败，等待重试。
     */
    int markRetryByOwner(
            @Param("consumerName") String consumerName,
            @Param("eventId") String eventId,
            @Param("lockedBy") String lockedBy,
            @Param("retryCount") Integer retryCount,
            @Param("nextRetryTime") LocalDateTime nextRetryTime,
            @Param("lastError") String lastError
    );

    /**
     * 重试耗尽。
     */
    int markDeadByOwner(
            @Param("consumerName") String consumerName,
            @Param("eventId") String eventId,
            @Param("lockedBy") String lockedBy,
            @Param("lastError") String lastError,
            @Param("processedTime") LocalDateTime processedTime
    );


    /**
     * 管理端分页查询 Inbox。
     */
    Page<InboxEvent> pageAdmin(@Param("query") InboxEventQueryDTO query);

    /**
     * 只有 DEAD 状态才能准备重放。
     */
    int replayDead(@Param("consumerName") String consumerName,
                   @Param("eventId") String eventId,
                   @Param("adminId") Long adminId,
                   @Param("now") LocalDateTime now);

    List<EventStatusCountVO> countByStatus();

    int deleteSuccessBefore(@Param("before") LocalDateTime before);
}
