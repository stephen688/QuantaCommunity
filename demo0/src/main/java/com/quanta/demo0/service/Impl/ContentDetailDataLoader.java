package com.quanta.demo0.service.Impl;

import com.quanta.demo0.content.entity.Content;
import com.quanta.demo0.content.entity.ContentImage;
import com.quanta.demo0.content.enums.ContentDetailState;
import com.quanta.demo0.mapper.ContentMapper;
import com.quanta.demo0.content.vo.ContentDetailCacheEntry;
import com.quanta.demo0.content.vo.ContentDetailSnapshot;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 从 MySQL 和图片表构造可共享的帖子详情快照。
 */
@Component
public class ContentDetailDataLoader {

    private static final int APPROVED_STATUS = 1;

    private final ContentMapper contentMapper;

    public ContentDetailDataLoader(ContentMapper contentMapper) {
        this.contentMapper = contentMapper;
    }

    public ContentDetailCacheEntry load(Long contentId) {
        Content content = contentMapper.selectById(contentId);
        if (content == null) {
            return new ContentDetailCacheEntry(ContentDetailState.NOT_FOUND, null);
        }
        if (Integer.valueOf(1).equals(content.getIsDeleted())) {
            return new ContentDetailCacheEntry(ContentDetailState.DELETED, null);
        }
        if (content.getAuditStatus() != null
                && content.getAuditStatus() != APPROVED_STATUS) {
            return new ContentDetailCacheEntry(ContentDetailState.NOT_APPROVED, null);
        }
        if (content.getPublishUserId() == null) {
            return new ContentDetailCacheEntry(ContentDetailState.INVALID_AUTHOR, null);
        }

        List<ContentImage> contentImages = contentMapper.selectImagesByContentIds(contentId);
        List<String> imageUrls = contentImages == null
                ? List.of()
                : contentImages.stream()
                .map(ContentImage::getImageUrl)
                .filter(StringUtils::isNotBlank)
                .toList();

        ContentDetailSnapshot snapshot = new ContentDetailSnapshot(
                content.getContentId(),
                content.getContentType(),
                content.getTitle(),
                content.getContent(),
                content.getPublishUserId(),
                content.getAuditStatus(),
                content.getCreateTime(),
                zeroIfNull(content.getLiked()),
                zeroIfNull(content.getCommentCount()),
                zeroIfNull(content.getCollectCount()),
                imageUrls
        );
        return new ContentDetailCacheEntry(ContentDetailState.FOUND, snapshot);
    }

    private int zeroIfNull(Integer value) {
        return value == null ? 0 : value;
    }
}
