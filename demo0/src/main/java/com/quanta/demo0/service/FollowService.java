package com.quanta.demo0.service;

import com.quanta.demo0.dto.FollowFeedQueryDTO;
import com.quanta.demo0.result.ScrollResult;
import com.quanta.demo0.vo.FollowResultVO;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface FollowService {
    FollowResultVO follow(Long id, boolean followed);



    ScrollResult getFollowFeed(FollowFeedQueryDTO followFeedQueryDTO);

    void pushToFollowersFeed(Long createTime, Integer contentType, Long publishUserId, Long contentId);


    void removeFeedFromFollowers(Long contentId, Integer contentType, Long publishUserId);

    /**
     * 根据 MySQL 当前帖子状态校准粉丝 Feed。
     */
    void reconcileContentFeed(Long contentId, Long fallbackPublishUserId, Integer fallbackContentType, Long fallbackCreateTime);
}
