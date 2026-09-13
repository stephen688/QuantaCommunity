package com.quanta.demo0.service;


import com.quanta.demo0.dto.ContentDTO;
import com.quanta.demo0.dto.ContentReportDTO;
import com.quanta.demo0.dto.RecommendQueryDTO;
import com.quanta.demo0.dto.SearchDTO;
import com.quanta.demo0.entity.Content;
import com.quanta.demo0.enums.AuditStatus;
import com.quanta.demo0.result.ScrollResult;
import com.quanta.demo0.vo.CollectResultVO;
import com.quanta.demo0.vo.ContentVO;
import com.quanta.demo0.vo.LikeResultVO;
import com.quanta.demo0.vo.PageVO;

import java.time.LocalDateTime;
import java.util.Map;

public interface ContentService {
    ContentVO publish(ContentDTO contentDTO);

    ScrollResult recommend(RecommendQueryDTO recommendQueryDTO);

    ContentVO getContentDetail(Long contentId);

    LikeResultVO likeContent(Long contentId, boolean liked);


PageVO<ContentVO> getMyContentList(Long userId, Integer current, Integer size, AuditStatus auditStatus);

    void deleteContent(Long contentId);

    CollectResultVO collect(Long contentId, boolean collected);

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

    void reportContent(ContentReportDTO contentReportDTO);

    PageVO<ContentVO> getMyBrowseHistoryContentList(Long userId, Integer current, Integer size);

    void clearBrowseHistory(Long userId);

    //查询用户主页帖子（已审核，未删除）
    PageVO<ContentVO> pageUserPublicContents(Long userId, Integer current, Integer size);
}
