package com.quanta.demo0.content.service.impl;

import com.quanta.demo0.content.entity.Content;
import com.quanta.demo0.content.service.ContentCounterService;
import com.quanta.demo0.content.vo.ContentSnapshotVO;
import com.quanta.demo0.content.mapper.ContentMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 内容域同步计数和轻量事实查询；不依赖互动实现，避免写路径循环注入。 */
@Service
@RequiredArgsConstructor
public class ContentCounterServiceImpl implements ContentCounterService {

    private final ContentMapper contentMapper;

    @Override
    public ContentSnapshotVO getContentSnapshot(Long contentId) {
        Content content = contentMapper.selectById(contentId);
        if (content == null) {
            return null;
        }
        return ContentSnapshotVO.builder()
                .contentId(content.getContentId())
                .contentType(content.getContentType())
                .title(content.getTitle())
                .content(content.getContent())
                .tags(content.getTags())
                .publishUserId(content.getPublishUserId())
                .auditStatus(content.getAuditStatus())
                .isDeleted(content.getIsDeleted())
                .createTime(content.getCreateTime())
                .updateTime(content.getUpdateTime())
                .likedCount(content.getLiked())
                .commentCount(content.getCommentCount())
                .collectCount(content.getCollectCount())
                .build();
    }

    @Override
    public int changeLikedCount(Long contentId, int delta) {
        return contentMapper.updateLiked(contentId, delta) ? 1 : 0;
    }

    @Override
    public int changeCollectCount(Long contentId, int delta) {
        return contentMapper.updateCollectCount(contentId, delta);
    }

    @Override
    public int changeCommentCount(Long contentId, int delta) {
        return contentMapper.updateCommentCount(contentId, delta);
    }
}
