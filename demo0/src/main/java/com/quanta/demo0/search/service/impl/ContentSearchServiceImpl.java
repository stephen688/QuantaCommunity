package com.quanta.demo0.search.service.impl;

import cn.hutool.core.util.BooleanUtil;
import com.github.pagehelper.Page;
import com.quanta.demo0.content.entity.Content;
import com.quanta.demo0.content.service.ContentQueryService;
import com.quanta.demo0.content.vo.ContentVO;
import com.quanta.demo0.platform.common.result.PageVO;
import com.quanta.demo0.platform.security.context.BaseContext;
import com.quanta.demo0.search.dto.SearchDTO;
import com.quanta.demo0.search.entity.SearchHistory;
import com.quanta.demo0.search.es.service.ElasticSearchService;
import com.quanta.demo0.search.exception.SearchFailedException;
import com.quanta.demo0.search.mapper.SearchMapper;
import com.quanta.demo0.search.service.ContentSearchService;
import com.quanta.demo0.user.service.AuthorProfileCache;
import com.quanta.demo0.user.vo.UserAuthInfoVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 搜索域内容检索实现。
 *
 * <p>Elasticsearch 只负责召回；作者资料和图片通过各自领域服务补齐，
 * 搜索历史作为非关键副作用失败时仅记录日志。</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ContentSearchServiceImpl implements ContentSearchService {

    private final ElasticSearchService elasticSearchService;
    private final SearchMapper searchMapper;
    private final AuthorProfileCache authorProfileCache;
    private final ContentQueryService contentQueryService;

    /**
     * 校验搜索条件、执行分页检索并组装内容响应。
     */
    @Override
    public PageVO<ContentVO> searchContent(SearchDTO searchDTO) {
        if (searchDTO == null || StringUtils.isBlank(searchDTO.getKeyword())) {
            throw new SearchFailedException("搜索关键词不能为空");
        }
        String keyword = searchDTO.getKeyword().trim();
        if (keyword.length() > 50) {
            throw new SearchFailedException("搜索关键词不能超过50字");
        }
        if (searchDTO.getContentType() != null
                && searchDTO.getContentType() != 1
                && searchDTO.getContentType() != 2) {
            throw new SearchFailedException("内容类型必须为1或2");
        }

        int current = searchDTO.getCurrent() == null || searchDTO.getCurrent() <= 0 ? 1 : searchDTO.getCurrent();
        int pageSize = searchDTO.getPageSize() == null || searchDTO.getPageSize() <= 0 ? 10 : searchDTO.getPageSize();
        Page<Content> page = elasticSearchService.searchContent(
                keyword, searchDTO.getContentType(), current, pageSize);
        List<Content> contents = page.getResult();
        recordHistory(BaseContext.getCurrentId(), keyword);

        if (contents == null || contents.isEmpty()) {
            return PageVO.<ContentVO>builder()
                    .list(List.of())
                    .pageNum(current)
                    .pageSize(pageSize)
                    .total(page.getTotal())
                    .totalPage(0)
                    .hasMore(false)
                    .build();
        }

        List<Long> authorIds = contents.stream().map(Content::getPublishUserId).distinct().toList();
        Map<Long, UserAuthInfoVO> authors = authorProfileCache.getAll(authorIds);
        if (authors == null) {
            authors = Collections.emptyMap();
        }
        Map<Long, UserAuthInfoVO> authorMap = authors;
        List<ContentVO> contentVOList = contents.stream()
                .map(content -> toVO(content,
                        authorMap.getOrDefault(content.getPublishUserId(), new UserAuthInfoVO())))
                .toList();
        long total = page.getTotal();
        int totalPages = (int) Math.ceil(total / (double) pageSize);
        return PageVO.<ContentVO>builder()
                .list(contentVOList)
                .pageNum(current)
                .pageSize(pageSize)
                .total(total)
                .totalPage(totalPages)
                .hasMore(current < totalPages)
                .build();
    }

    private void recordHistory(Long userId, String keyword) {
        try {
            SearchHistory existing = searchMapper.selectByUserIdAndKeyword(userId, keyword);
            if (existing != null) {
                searchMapper.updateSearchTime(userId, keyword, LocalDateTime.now());
            } else {
                searchMapper.insertSearchHistory(SearchHistory.builder()
                        .userId(userId)
                        .keyword(keyword)
                        .isDeleted(0)
                        .createTime(LocalDateTime.now())
                        .build());
            }
        } catch (Exception e) {
            log.error("记录搜索历史失败:userId={},keyword={}", userId, keyword, e);
        }
    }

    private ContentVO toVO(Content content, UserAuthInfoVO author) {
        return ContentVO.builder()
                .contentId(content.getContentId())
                .contentType(content.getContentType())
                .title(content.getTitle())
                .content(content.getContent())
                .liked(content.getLiked() == null ? 0 : content.getLiked())
                .commentCount(content.getCommentCount() == null ? 0 : content.getCommentCount())
                .collectCount(content.getCollectCount() == null ? 0 : content.getCollectCount())
                .publishUserId(content.getPublishUserId())
                .avatarUrl(author.getAvatarUrl())
                .nickName(author.getNickName())
                .quantaDepartment(author.getQuantaDepartment())
                .quantaBatch(author.getQuantaBatch())
                .auditStatus(content.getAuditStatus())
                .createTime(content.getCreateTime())
                .images(contentQueryService.getContentImageUrls(content.getContentId()))
                .isLiked(BooleanUtil.isTrue(content.getIsLiked()))
                .isCollected(BooleanUtil.isTrue(content.getIsCollected()))
                .build();
    }
}
