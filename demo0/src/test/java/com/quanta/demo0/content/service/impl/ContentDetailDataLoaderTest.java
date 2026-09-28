package com.quanta.demo0.content.service.impl;

import com.quanta.demo0.content.entity.Content;
import com.quanta.demo0.content.entity.ContentImage;
import com.quanta.demo0.mapper.ContentMapper;
import com.quanta.demo0.content.enums.ContentDetailState;
import com.quanta.demo0.content.vo.ContentDetailCacheEntry;
import com.quanta.demo0.content.vo.ContentDetailSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ContentDetailDataLoaderTest {

    private static final Long CONTENT_ID = 42L;

    @Mock
    private ContentMapper contentMapper;

    private ContentDetailDataLoader loader;

    @BeforeEach
    void setUp() {
        loader = new ContentDetailDataLoader(contentMapper);
    }

    @Test
    void loadsFoundSnapshotAndQueriesImagesOnceWhileFilteringBlankUrls() {
        LocalDateTime createTime = LocalDateTime.of(2026, 9, 20, 10, 15);
        when(contentMapper.selectById(CONTENT_ID)).thenReturn(Content.builder()
                .contentId(CONTENT_ID)
                .contentType(2)
                .title("title")
                .content("body")
                .publishUserId(7L)
                .auditStatus(1)
                .liked(3)
                .commentCount(4)
                .collectCount(5)
                .createTime(createTime)
                .isDeleted(0)
                .build());
        when(contentMapper.selectImagesByContentIds(CONTENT_ID)).thenReturn(List.of(
                ContentImage.builder().imageUrl("https://cdn/1.png").build(),
                ContentImage.builder().imageUrl(" ").build(),
                ContentImage.builder().imageUrl(null).build(),
                ContentImage.builder().imageUrl("https://cdn/2.png").build()
        ));

        ContentDetailCacheEntry result = loader.load(CONTENT_ID);

        assertThat(result.state()).isEqualTo(ContentDetailState.FOUND);
        assertThat(result.snapshot()).isEqualTo(new ContentDetailSnapshot(
                CONTENT_ID,
                2,
                "title",
                "body",
                7L,
                1,
                createTime,
                3,
                4,
                5,
                List.of("https://cdn/1.png", "https://cdn/2.png")
        ));
        verify(contentMapper).selectImagesByContentIds(CONTENT_ID);
    }

    @Test
    void returnsNotFoundWithoutQueryingImagesWhenContentDoesNotExist() {
        when(contentMapper.selectById(CONTENT_ID)).thenReturn(null);

        ContentDetailCacheEntry result = loader.load(CONTENT_ID);

        assertThat(result).isEqualTo(new ContentDetailCacheEntry(ContentDetailState.NOT_FOUND, null));
        verify(contentMapper, never()).selectImagesByContentIds(CONTENT_ID);
    }

    @Test
    void returnsDeletedWithoutQueryingImagesWhenContentIsSoftDeleted() {
        when(contentMapper.selectById(CONTENT_ID)).thenReturn(Content.builder()
                .contentId(CONTENT_ID)
                .isDeleted(1)
                .publishUserId(7L)
                .auditStatus(1)
                .build());

        ContentDetailCacheEntry result = loader.load(CONTENT_ID);

        assertThat(result).isEqualTo(new ContentDetailCacheEntry(ContentDetailState.DELETED, null));
        verify(contentMapper, never()).selectImagesByContentIds(CONTENT_ID);
    }

    @Test
    void returnsNotApprovedWithoutQueryingImagesWhenAuditStatusIsNotApproved() {
        when(contentMapper.selectById(CONTENT_ID)).thenReturn(Content.builder()
                .contentId(CONTENT_ID)
                .isDeleted(0)
                .publishUserId(7L)
                .auditStatus(2)
                .build());

        ContentDetailCacheEntry result = loader.load(CONTENT_ID);

        assertThat(result).isEqualTo(new ContentDetailCacheEntry(ContentDetailState.NOT_APPROVED, null));
        verify(contentMapper, never()).selectImagesByContentIds(CONTENT_ID);
    }

    @Test
    void returnsInvalidAuthorWithoutQueryingImagesWhenPublisherIsMissing() {
        when(contentMapper.selectById(CONTENT_ID)).thenReturn(Content.builder()
                .contentId(CONTENT_ID)
                .isDeleted(0)
                .publishUserId(null)
                .auditStatus(1)
                .build());

        ContentDetailCacheEntry result = loader.load(CONTENT_ID);

        assertThat(result).isEqualTo(new ContentDetailCacheEntry(ContentDetailState.INVALID_AUTHOR, null));
        verify(contentMapper, never()).selectImagesByContentIds(CONTENT_ID);
    }
}
