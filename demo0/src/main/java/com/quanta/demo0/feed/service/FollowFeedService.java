package com.quanta.demo0.feed.service;

import com.quanta.demo0.feed.dto.FollowFeedQueryDTO;
import com.quanta.demo0.platform.common.result.ScrollResult;

/**
 * 关注流读写与投影服务。
 */
public interface FollowFeedService {

    ScrollResult getFollowFeed(FollowFeedQueryDTO followFeedQueryDTO);

    void pushToFollowersFeed(Long createTime, Integer contentType, Long publishUserId, Long contentId);

    void removeFeedFromFollowers(Long contentId, Integer contentType, Long publishUserId);

    /**
     * 根据 MySQL 当前帖子状态校准粉丝 Feed。
     */
    void reconcileContentFeed(Long contentId, Long fallbackPublishUserId, Integer fallbackContentType,
                              Long fallbackCreateTime);

    /**
     * 关注关系变更提交后同步对应的 Feed 投影。
     */
    void syncFollowChange(Long followerId, Long followedUserId, boolean followed);
}
