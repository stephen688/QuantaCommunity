package com.quanta.demo0.follow.service;

import java.util.List;

/**
 * 关注关系查询服务。
 *
 * 职责：向其他领域公开关注统计和当前查看者的关注状态；
 * 边界：不暴露 Follow 持久化实体，不承担关注写入和 Feed 同步。
 */
public interface FollowQueryService {

    /**
     * 查询用户关注数量。
     *
     * @param userId 用户 ID
     * @return 关注数量
     */
    Integer countFollowing(Long userId);

    /**
     * 查询用户粉丝数量。
     *
     * @param userId 用户 ID
     * @return 粉丝数量
     */
    Integer countFollowers(Long userId);

    /**
     * 查询查看者是否关注目标用户。
     *
     * @param viewerId 查看者 ID
     * @param targetUserId 目标用户 ID
     * @return 当前关注状态
     */
    boolean isFollowing(Long viewerId, Long targetUserId);

    /**
     * 查询指定发布者的粉丝 ID，供 Feed 推送使用。
     *
     * @param publishUserId 发布者 ID
     * @return 粉丝 ID 列表，保持关注 Mapper 的查询顺序
     */
    List<Long> getFollowerIds(Long publishUserId);

    /**
     * 查询指定用户关注的用户 ID，供 Feed 关注流使用。
     *
     * @param userId 用户 ID
     * @return 关注用户 ID 列表，保持关注 Mapper 的查询顺序
     */
    List<Long> getFollowedUserIds(Long userId);
}
