package com.quanta.demo0.search.service.impl;

import com.github.pagehelper.Page;
import com.quanta.demo0.content.service.ContentQueryService;
import com.quanta.demo0.content.vo.ContentVO;
import com.quanta.demo0.platform.common.result.PageVO;
import com.quanta.demo0.search.dto.SearchDTO;
import com.quanta.demo0.search.es.document.ContentDocument;
import com.quanta.demo0.search.mapper.SearchMapper;
import com.quanta.demo0.search.service.ContentIndexService;
import com.quanta.demo0.user.service.AuthorProfileCache;
import com.quanta.demo0.user.vo.UserAuthInfoVO;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 搜索域图片装配测试。
 *
 * <p>职责：验证搜索结果保持 ES 顺序，并从一次批量图片查询中按内容 ID 回填图片。
 * 边界：ES、作者缓存、搜索历史和内容查询均使用 mock，不验证外部依赖或 Mapper SQL。</p>
 */
class ContentSearchServiceImplImagesTest {

    @Test
    void searchKeepsEsOrderAndAssociatesImagesFromOneBatch() {
        ContentIndexService contentIndexService = mock(ContentIndexService.class);
        SearchMapper searchMapper = mock(SearchMapper.class);
        AuthorProfileCache authorProfileCache = mock(AuthorProfileCache.class);
        ContentQueryService contentQueryService = mock(ContentQueryService.class);
        ContentSearchServiceImpl service = new ContentSearchServiceImpl(
                contentIndexService, searchMapper, authorProfileCache, contentQueryService);

        Page<ContentDocument> page = new Page<>(1, 3);
        page.addAll(List.of(
                content(101L, 11L),
                content(102L, 12L),
                content(103L, 11L)));
        page.setTotal(3);
        when(contentIndexService.searchContent("keyword", null, 1, 3)).thenReturn(page);
        when(authorProfileCache.getAll(List.of(11L, 12L)))
                .thenReturn(Map.of(11L, new UserAuthInfoVO(), 12L, new UserAuthInfoVO()));
        when(contentQueryService.getContentImageUrlsBatch(List.of(101L, 102L, 103L)))
                .thenReturn(Map.of(
                        101L, List.of("image-101"),
                        103L, List.of("image-103-a", "image-103-b")));

        PageVO<ContentVO> result = service.searchContent(SearchDTO.builder()
                .keyword("keyword")
                .current(1)
                .pageSize(3)
                .build());

        assertThat(result.getList()).extracting(ContentVO::getContentId)
                .containsExactly(101L, 102L, 103L);
        assertThat(result.getList().get(0).getImages()).containsExactly("image-101");
        assertThat(result.getList().get(1).getImages()).isEmpty();
        assertThat(result.getList().get(2).getImages())
                .containsExactly("image-103-a", "image-103-b");
        verify(contentQueryService).getContentImageUrlsBatch(List.of(101L, 102L, 103L));
        verify(contentQueryService, never()).getContentImageUrls(anyLong());
    }

    private ContentDocument content(Long contentId, Long publishUserId) {
        return ContentDocument.builder()
                .contentId(contentId)
                .publishUserId(publishUserId)
                .contentType(1)
                .title("title-" + contentId)
                .content("content-" + contentId)
                .auditStatus(1)
                .isDeleted(0)
                .build();
    }
}
