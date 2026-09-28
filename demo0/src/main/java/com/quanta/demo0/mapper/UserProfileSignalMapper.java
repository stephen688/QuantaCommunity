package com.quanta.demo0.mapper;

import com.quanta.demo0.feed.entity.UserProfileSignal;
import org.apache.ibatis.annotations.*;
import java.util.List;

/** 偏好事实访问：锁住用户行串行提交事实，防并发快照及新旧事务交错。 */
@Mapper
public interface UserProfileSignalMapper {
    /** 目标必须存在、未删除；返回账户状态，消费时不依赖 HTTP 上下文。 */
    @Select("SELECT account_status FROM tb_user WHERE id=#{userId} AND is_deleted=0 FOR UPDATE")
    Integer lockUser(@Param("userId") Long userId);
    UserProfileSignal findEvent(@Param("eventId") String eventId);
    int insert(UserProfileSignal signal);
    Long generation(@Param("userId") Long userId);
    /** 返回各记忆最新状态（含 DELETE）；排序保证同主题最后明确表达优先。 */
    List<UserProfileSignal> latestStates(@Param("userId") Long userId);
}
