package com.quanta.demo0.rag.generation;
import com.quanta.demo0.entity.Content;
import com.quanta.demo0.entity.ContentImage;
import com.quanta.demo0.entity.UserAuthInfo;
import com.quanta.demo0.mapper.ContentMapper;
import com.quanta.demo0.mapper.UserMapper;
import com.quanta.demo0.properties.RagProperties;
import com.quanta.demo0.rag.model.RagAnswer;
import com.quanta.demo0.rag.model.RagCandidate;
import com.quanta.demo0.rag.model.RagSearchRequest;
import com.quanta.demo0.rag.model.RagSearchResponse;
import com.quanta.demo0.rag.retrieval.RagRetrieveFacade;
import com.quanta.demo0.vo.ContentVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
        * RAG 搜索编排服务
 * ============================
         * 作用说明
 * ============================
         * 这个类是 RAG 主流程的"总指挥"，负责编排完整的搜索+AI 生成链路。
        * 在 RAG 架构中的角色：
        *   用户请求 → RagSearchService（编排） → 返回 RagSearchResponse（AI 总结 + 帖子列表）
        * 核心职责：
        *   1. 校验请求参数
 *   2. 调用 RagRetrieveFacade 获取融合 Top10
 *   3. 将融合结果映射为帖子列表（ContentVO）
        *   4. 如 enableAi=true 且有参考资料，则调用 RagGenerationService
 *   5. 封装 RagSearchResponse 返回
 * ============================
         * 执行流程（一步步拆解）
        * ============================
        * 第 1 步：校验请求参数
 *   检查 RAG 总开关是否开启
 *   检查 query 是否为空
 * 第 2 步：调用双路检索
 *   调用 ragRetrieveFacade.retrieveAndFuse(query, contentType)
 *   获取融合排序后的候选列表（Top10）
        * 第 3 步：将候选映射为 ContentVO
 *   提取所有 contentId，批量查询 Content 实体
 *   批量查询用户信息（UserAuthInfo）
        *   批量查询图片列表（Image）
        *   组装 ContentVO 列表
 * 第 4 步：AI 生成
        *   如果 enableAi=true 且参考资料不为空
 *   调用 ragGenerationService.generateSummary(query, candidates)
 *   成功 → 封装 RagAnswer(enabled=true, content=总结)
 *   失败 → 封装 RagAnswer(enabled=false, reason=失败原因)
 *   如果 enableAi=false → RagAnswer 为 null
        * 第 5 步：封装响应
 *   构造 RagSearchResponse(aiAnswer, list, total, hasMore)
 *   返回给 Controller
 * ============================
         * 异常处理策略
 * ============================
         * 检索失败：抛出 RagRetrieveException，由全局异常处理器兜底
 * AI 失败：不影响主流程，仅置 aiAnswer.enabled=false
        * 帖子查询失败：跳过该帖子，继续处理其他帖子
 */
  @Service
  @Slf4j
public class RagSearchService {

      @Autowired
      private RagProperties ragProperties;
      @Autowired
        private RagRetrieveFacade ragRetrieveFacade;
      @Autowired
        private ContentMapper contentMapper;
      @Autowired
      private RagGenerationService ragGenerationService;
      @Autowired
      private UserMapper userMapper;
      @Autowired
      private RagSummaryCacheKeyBuilder cacheKeyBuilder;
        @Autowired
        private StringRedisTemplate stringRedisTemplate;
      public RagSearchResponse searchWithAi(RagSearchRequest request) {
          // 1. 校验rag总开关
          if (!ragProperties.isEnabled()) {
              log.warn("RAG 搜索被禁用");
              return null;
          }

          String query = request.getQuery();
          Integer contentType = request.getContentType();
            boolean enableAi = request.getEnableAi();
            // 2. 调用双路检索
          List<RagCandidate> candidates =
                  ragRetrieveFacade.retrieveAndFuse(query, contentType);

          if (candidates == null || candidates.isEmpty()) {
              log.info("RAG 搜索未命中任何结果");
              return buildEmptyResponse();

          }
          //3. 将候选映射为 ContentVO 列表
          List<ContentVO> contentVOList =convertCandidatesToVO(candidates);


          //4. AI 生成
          RagAnswer aiAnswer = null;
          if (enableAi && !candidates.isEmpty()) {
              aiAnswer = generateAiAnswer(query, candidates);
          }
          //5. 封装响应
          RagSearchResponse response = RagSearchResponse.builder()
                  .aiAnswer(aiAnswer)
                  .list(contentVOList)
                  .total((long)contentVOList.size())
                  .hasMore(false) // 固定10条，不分页，所以没有更多了
                  .build();

          log.info("RAG 搜索完成: query='{}'", query);
            return response;



          }


    /**
     * 生成 AI 总结（含 Redis 缓存）
     * ============================
     * @param query       用户搜索关键词
     * @param candidates  融合排序后的候选列表
     * @return RagAnswer AI 总结结果
     *   成功 → enabled=true, content=总结文本
     *   失败 → enabled=false, reason=失败原因
     */
    private RagAnswer generateAiAnswer(String query, List<RagCandidate> candidates) {
        // 1. 检查缓存开关
        if (!ragProperties.isSummaryCacheEnabled()) {
            return generateAiAnswerWithoutCache(query, candidates);
        }

        // 2. 构建缓存 key
        String cacheKey = cacheKeyBuilder.buildCacheKey(query, null, candidates, ragProperties);

        // 3. 尝试从 Redis 读取缓存
        try {
            String cachedSummary = stringRedisTemplate.opsForValue().get(cacheKey);
            if (cachedSummary != null && !cachedSummary.isEmpty()) {
                log.info("[RAG-SEARCH] 总结缓存命中: key={}", cacheKey);
                return RagAnswer.builder()
                        .enabled(true)
                        .content(cachedSummary)
                        .reason(null)
                        .build();
            }
        } catch (Exception e) {
            log.warn("[RAG-SEARCH] Redis 读取失败，降级直调 LLM: {}", e.getMessage());
        }

        // 4. 缓存未命中，调用 LLM
        return generateAiAnswerWithCacheWrite(query, candidates, cacheKey);
    }

    /**
     * 不使用缓存的 AI 生成（缓存开关关闭时）
     */
    private RagAnswer generateAiAnswerWithoutCache(String query, List<RagCandidate> candidates) {
        try {
            Optional<String> summaryOpt = ragGenerationService.generateSummary(query, candidates);

            if (summaryOpt.isPresent()) {
                return RagAnswer.builder()
                        .enabled(true)
                        .content(summaryOpt.get())
                        .reason(null)
                        .build();
            } else {
                log.warn("[RAG-SEARCH] AI 生成返回空结果");
                return RagAnswer.builder()
                        .enabled(false)
                        .content(null)
                        .reason("AI 生成返回空结果")
                        .build();
            }
        } catch (Exception e) {
            log.error("[RAG-SEARCH] AI 生成异常", e);
            return RagAnswer.builder()
                    .enabled(false)
                    .content(null)
                    .reason("AI 生成异常: " + e.getMessage())
                    .build();
        }
    }

    /**
     * 调用 LLM 并写入缓存（按 Plan 1.3 失败矩阵）
     */
    private RagAnswer generateAiAnswerWithCacheWrite(String query, List<RagCandidate> candidates, String cacheKey) {
        try {
            Optional<String> summaryOpt = ragGenerationService.generateSummary(query, candidates);

            if (summaryOpt.isPresent()) {
                String summary = summaryOpt.get();
                // 成功：写入 Redis，使用正常 TTL
                try {
                    stringRedisTemplate.opsForValue().set(
                            cacheKey,
                            summary,
                            ragProperties.getSummaryCacheTtlSeconds(),
                            java.util.concurrent.TimeUnit.SECONDS
                    );
                    log.info("[RAG-SEARCH] 总结已缓存: key={}, ttl={}s", cacheKey, ragProperties.getSummaryCacheTtlSeconds());
                } catch (Exception e) {
                    log.warn("[RAG-SEARCH] Redis 写入失败，不影响主流程: {}", e.getMessage());
                }

                return RagAnswer.builder()
                        .enabled(true)
                        .content(summary)
                        .reason(null)
                        .build();
            } else {
                log.warn("[RAG-SEARCH] AI 生成返回空结果");
                // 空结果：不缓存（或可选短 TTL 占位）
                return RagAnswer.builder()
                        .enabled(false)
                        .content(null)
                        .reason("AI 生成返回空结果")
                        .build();
            }
        } catch (Exception e) {
            log.error("[RAG-SEARCH] AI 生成异常", e);
            // 异常：不缓存
            return RagAnswer.builder()
                    .enabled(false)
                    .content(null)
                    .reason("AI 生成异常: " + e.getMessage())
                    .build();
        }
    }


    /**
     *
     * @return
     */
    private RagSearchResponse buildEmptyResponse() {

        return RagSearchResponse.builder()
                .aiAnswer(null)
                .list(Collections.emptyList())
                .total(0L)
                .hasMore(false)
                .build();
    }

    /**
     * 将候选列表转换为 ContentVO 列表
     * ============================
     * @param candidates 融合排序后的候选列表
     * @return List<ContentVO> 帖子视图对象列表
     */
    private List<ContentVO> convertCandidatesToVO(List<RagCandidate> candidates) {
        // 提取所有 contentId（去重，避免同一问题多次查询）
        List<Long> contentIds = candidates.stream()
                .map(RagCandidate::getContentId)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());

        if (contentIds.isEmpty()) {
            return Collections.emptyList();
        }

        // 批量查询 Content 实体
        List<Content> contents = contentMapper.selectBatchIds(contentIds);
        if (contents == null || contents.isEmpty()) {
            log.warn("[RAG-SEARCH] 批量查询 Content 为空");
            return Collections.emptyList();
        }

        // 构建 contentId → Content 映射
        Map<Long, Content> contentMap = contents.stream()
                .collect(Collectors.toMap(Content::getContentId, c -> c, (v1, v2) -> v1));

        // 收集所有用户 ID
        List<Long> userIds = contents.stream()
                .map(Content::getPublishUserId)
                .distinct()
                .collect(Collectors.toList());

        // 批量查询用户信息
        List<UserAuthInfo> userAuthList = userIds.isEmpty()
                ? Collections.emptyList()
                : userMapper.selectUserAuthInfoByIds(userIds);

        Map<Long, UserAuthInfo> userAuthMap = userAuthList.stream()
                .collect(Collectors.toMap(UserAuthInfo::getUserId, u -> u, (v1, v2) -> v1));

        // 批量查询图片
        // N+1 模式：循环逐个查询图片
        Map<Long, List<String>> imageMap = new HashMap<>();
        for (Long contentId : contentIds) {
            List<ContentImage> images = contentMapper.selectImagesByContentIds(contentId);
            if (images != null && !images.isEmpty()) {
                List<String> imageUrls = images.stream()
                        .map(ContentImage::getImageUrl)
                        .collect(Collectors.toList());
                imageMap.put(contentId, imageUrls);
            }
        }
        // 按候选顺序组装 ContentVO（保持融合排序顺序）
        List<ContentVO> voList = new ArrayList<>();
        for (RagCandidate candidate : candidates) {
            Content content = contentMap.get(candidate.getContentId());
            if (content == null) {
                continue; // 跳过不存在的帖子
            }

            UserAuthInfo userInfo = userAuthMap.getOrDefault(content.getPublishUserId(), new UserAuthInfo());
            List<String> imageUrls = imageMap.getOrDefault(candidate.getContentId(), Collections.emptyList());

            ContentVO vo = ContentVO.builder()
                    .contentId(content.getContentId())
                    .contentType(content.getContentType())
                    .title(content.getTitle())
                    .content(content.getContent())
                    .publishUserId(content.getPublishUserId())
                    .auditStatus(content.getAuditStatus())
                    .createTime(content.getCreateTime())
                    .images(imageUrls)
                    .avatarUrl(userInfo.getAvatarUrl())
                    .nickName(userInfo.getNickName())
                    .quantaDepartment(userInfo.getQuantaDepartment())
                    .quantaBatch(userInfo.getQuantaBatch())
                    .liked(content.getLiked() != null ? content.getLiked() : 0)
                    .commentCount(content.getCommentCount() != null ? content.getCommentCount() : 0)
                    .collectCount(content.getCollectCount() != null ? content.getCollectCount() : 0)
                    .isLiked(false) // RAG 搜索不查点赞状态
                    .isCollected(false) // RAG 搜索不查收藏状态
                    .build();

            voList.add(vo);
        }

        return voList;
    }

}
