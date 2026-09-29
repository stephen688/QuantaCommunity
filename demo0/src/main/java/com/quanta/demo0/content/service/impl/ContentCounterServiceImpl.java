package com.quanta.demo0.content.service.impl;

import com.quanta.demo0.content.entity.Content;
import com.quanta.demo0.content.service.ContentCounterService;
import com.quanta.demo0.content.vo.ContentSnapshotVO;
import com.quanta.demo0.mapper.ContentMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

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
                .publishUserId(content.getPublishUserId())
                .auditStatus(content.getAuditStatus())
                .isDeleted(content.getIsDeleted())
                .createTime(content.getCreateTime())
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
}
