package com.quanta.demo0.service.Impl;

import com.alibaba.fastjson.JSON;
import com.quanta.demo0.constant.RedisConstants;
import com.quanta.demo0.entity.BaseContext;
import com.quanta.demo0.entity.Content;
import com.quanta.demo0.entity.UserAuthInfo;
import com.quanta.demo0.exception.SearchFailedException;
import com.quanta.demo0.mapper.ContentMapper;
import com.quanta.demo0.mapper.SearchMapper;
import com.quanta.demo0.mapper.UserMapper;
import com.quanta.demo0.properties.SearchTrendingProperties;
import com.quanta.demo0.service.SearchService;
import com.quanta.demo0.vo.HotAlumniVO;
import com.quanta.demo0.vo.HotQuestionVO;
import com.quanta.demo0.vo.SearchTrendingVO;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.quanta.demo0.constant.RedisConstants.*;

/**
 * 搜索服务实现类。
 *
 * 核心职责：
 * 1. 提供搜索历史查询、删除与清空等用户侧搜索管理能力；
 * 2. 聚合热门问题、热门校友、搜索热词等发现类数据；
 * 3. 协调数据库与 Redis 缓存，提升高频搜索场景响应性能。
 *
 * 设计说明：
 * - 历史记录以数据库为准，缓存用于热点榜单加速与降压；
 * - 对搜索输入与返回结果做统一校验，避免脏数据透出。
 */
@Service
@Slf4j
public class SearchServiceImpl implements SearchService {

    @Autowired
    private SearchMapper searchMapper;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private SearchTrendingProperties searchTrendingProperties;
    @Autowired
    private ContentMapper contentMapper;

    @Autowired
    private UserMapper userMapper;
    @Override
    public List<String> getSearchHistoryKeywords() {
        //1. 获取当前用户ID
        Long userId = BaseContext.getCurrentId();
        //2.查询数据库搜索历史
       List<String>history= searchMapper.selectSearchHistoryKeywords(userId);
       //3.去重，且限制前20条
        return history.stream().
                distinct().limit(20).toList();

    }

    @Override
    public void clearSearchHistory() {
        //1. 获取当前用户ID
        Long userId = BaseContext.getCurrentId();
        //2.删除数据库搜索历史
       int rows= searchMapper.softDeleteAllByUserId(userId);

       log.info("清除搜索历史，用户ID: {}, 删除记录数: {}", userId, rows);

    }

    @Override
    public void deleteOneSearchHistory(Long id) {
        if (id == null) {
            throw new SearchFailedException("搜索历史ID不能为空");
        }
        //1. 获取当前用户ID
        Long userId = BaseContext.getCurrentId();
        //2.删除数据库搜索历史
      int rows=  searchMapper.softDeleteSearchHistoryByIdAndUserId(id,userId);
        if(rows==0){
            throw new SearchFailedException("删除搜索历史失败");
        }
    }


    /**
     *  搜索热门
     * @return
     */

    @Override
    public SearchTrendingVO getTrending() {
        //1. 尝试从缓存读取
        String cacheJson = stringRedisTemplate.opsForValue().get(SEARCH_TRENDING_ALL_KEY);
        if (StringUtils.isNotBlank(cacheJson)) {
            try {
                return JSON.parseObject(cacheJson, SearchTrendingVO.class);
            } catch (Exception e) {
                log.warn("解析热门发现缓存失败，重新查询", e);
            }
        }

        //2. 构建热门发现数据
        SearchTrendingVO vo = new SearchTrendingVO();

        //2.1 热门关键词（DB 全站聚合）
        List<String> keywords = searchMapper.selectHotKeywords(searchTrendingProperties.getKeywordLimit());
        if (keywords == null || keywords.isEmpty()) {
            // 冷启动兜底：默认热门词
            keywords = List.of("Spring Boot", "考研复习", "露营", "校友聚会", "小程序分包");
        }
        vo.setHotKeywords(keywords);
        //2.2 热门问题（Redis ZSET + DB 兜底）
        List<HotQuestionVO> questions = new ArrayList<>();
        Set<String> contentIds = stringRedisTemplate.opsForZSet()
                .reverseRange(RECOMMEND_HOT_ALL_KEY, 0, searchTrendingProperties.getQuestionLimit() * 2 - 1);
        if (contentIds != null && !contentIds.isEmpty()) {
            List<Long> ids = contentIds.stream().map(Long::parseLong).collect(Collectors.toList());
            List<Content> contents = contentMapper.selectBatchIds(ids);
            for (Content c : contents) {
                if (c.getIsDeleted() == 0 && c.getAuditStatus() == 1 && questions.size() < searchTrendingProperties.getQuestionLimit()) {
                    questions.add(HotQuestionVO.builder()
                            .contentId(c.getContentId())
                            .title(c.getTitle())
                            .liked(c.getLiked())
                            .build());
                }
            }
        }
        // DB 兜底（Redis 空或不足）
        if (questions.size() < searchTrendingProperties.getQuestionLimit()) {
            List<Content> dbContents = contentMapper.selectTopLikedContents(searchTrendingProperties.getQuestionLimit());
            for (Content c : dbContents) {
                if (c.getIsDeleted() == 0 && c.getAuditStatus() == 1
                        && questions.stream().noneMatch(q -> q.getContentId().equals(c.getContentId()))) {
                    questions.add(HotQuestionVO.builder()
                            .contentId(c.getContentId())
                            .title(c.getTitle())
                            .liked(c.getLiked())
                            .build());
                }
            }
        }
        vo.setHotQuestions(questions);

        //2.3 热门校友（Redis ZSET + DB 兜底）
        List<HotAlumniVO> alumni = new ArrayList<>();
        Set<String> userIds = stringRedisTemplate.opsForZSet()
                .reverseRange(USER_FOLLOWER_RANK_KEY, 0, searchTrendingProperties.getAlumniLimit() * 2 - 1);
        if (userIds != null && !userIds.isEmpty()) {
            List<Long> uidList = userIds.stream().map(Long::parseLong).collect(Collectors.toList());
            List<UserAuthInfo> users = userMapper.selectUserAuthInfoByIds(uidList);
            for (UserAuthInfo u : users) {
                Integer status = u.getAccountStatus();
                if ((status == null || status == 0) && alumni.size() < searchTrendingProperties.getAlumniLimit()) {
                    alumni.add(HotAlumniVO.builder()
                            .userId(u.getUserId())
                            .nickName(u.getNickName())
                            .avatarUrl(u.getAvatarUrl())
                            .build());
                }
            }
        }

        // DB 兜底（Redis 空或不足）
        if (alumni.size() < searchTrendingProperties.getAlumniLimit()) {
            List<UserAuthInfo> dbUsers = userMapper.selectTopFollowedUsers(searchTrendingProperties.getAlumniLimit());
            for (UserAuthInfo u : dbUsers) {
                Integer status = u.getAccountStatus();
                if ((status == null || status == 0)
                        && alumni.stream().noneMatch(a -> a.getUserId().equals(u.getUserId()))) {
                    alumni.add(HotAlumniVO.builder()
                            .userId(u.getUserId())
                            .nickName(u.getNickName())
                            .avatarUrl(u.getAvatarUrl())
                            .build());
                }
            }
        }
        vo.setHotAlumni(alumni);

        //3. 写入缓存
        try {
            String json = JSON.toJSONString(vo);
            stringRedisTemplate.opsForValue().set(RedisConstants.SEARCH_TRENDING_ALL_KEY, json, searchTrendingProperties.getCacheTtl(), TimeUnit.SECONDS);
        } catch (Exception e) {
            log.error("写入热门发现缓存失败", e);
        }

        return vo;
    }
}
