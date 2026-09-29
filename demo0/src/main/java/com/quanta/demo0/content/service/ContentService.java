package com.quanta.demo0.content.service;


import com.quanta.demo0.content.dto.ContentDTO;
import com.quanta.demo0.feed.dto.RecommendQueryDTO;
import com.quanta.demo0.search.dto.SearchDTO;
import com.quanta.demo0.content.entity.Content;
import com.quanta.demo0.platform.common.enums.AuditStatus;
import com.quanta.demo0.platform.common.result.ScrollResult;
import com.quanta.demo0.content.vo.ContentVO;
import com.quanta.demo0.platform.common.result.PageVO;

import java.time.LocalDateTime;
import java.util.Map;

public interface ContentService {
    ContentVO publish(ContentDTO contentDTO);

    ScrollResult recommend(RecommendQueryDTO recommendQueryDTO);

    ContentVO getContentDetail(Long contentId);

PageVO<ContentVO> getMyContentList(Long userId, Integer current, Integer size, AuditStatus auditStatus);

    void deleteContent(Long contentId);

    PageVO<ContentVO> getMyLikedContentList(Long userId, Integer current, Integer size);

    PageVO<ContentVO> getMyCollectContentList(Long userId, Integer current, Integer size);

    PageVO<ContentVO> searchContent(SearchDTO searchDTO);

    void publishToRedis(Long contentId, LocalDateTime createTime, Integer contentType);

    double calculateHotScore(Content content);

    /**
     * 根据 MySQL 当前状态重新计算并覆盖 Redis 热度。
     */
    void reconcileHotScore(Long contentId);

     String resolveRecommendHotKey(Integer contentType);

    PageVO<ContentVO> getMyBrowseHistoryContentList(Long userId, Integer current, Integer size);

    void clearBrowseHistory(Long userId);

    //查询用户主页帖子（已审核，未删除）
    PageVO<ContentVO> pageUserPublicContents(Long userId, Integer current, Integer size);
}
