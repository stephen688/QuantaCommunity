package com.quanta.demo0.content.service.impl;

import cn.hutool.core.util.BooleanUtil;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.quanta.demo0.content.entity.Content;
import com.quanta.demo0.content.entity.ContentImage;
import com.quanta.demo0.content.enums.ContentDetailState;
import com.quanta.demo0.content.exception.ContentFailedException;
import com.quanta.demo0.content.service.ContentDetailCacheService;
import com.quanta.demo0.content.service.ContentQueryService;
import com.quanta.demo0.content.vo.ContentDetailCacheEntry;
import com.quanta.demo0.content.vo.ContentDetailSnapshot;
import com.quanta.demo0.content.vo.ContentSnapshotVO;
import com.quanta.demo0.content.vo.ContentVO;
import com.quanta.demo0.interaction.service.BrowseHistoryService;
import com.quanta.demo0.interaction.service.ContentInteractionService;
import com.quanta.demo0.content.mapper.ContentMapper;
import com.quanta.demo0.user.service.UserQueryService;
import com.quanta.demo0.platform.common.enums.AuditStatus;
import com.quanta.demo0.platform.common.result.PageVO;
import com.quanta.demo0.platform.security.context.BaseContext;
import com.quanta.demo0.user.service.AuthorProfileCache;
import com.quanta.demo0.user.vo.UserAuthInfoVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 内容读模型服务。
 *
 * <p>提供稳定事实快照和访问者读模型；缓存仅保存稳定字段，互动状态逐请求补齐。</p>
 */
@Service
public class ContentQueryServiceImpl implements ContentQueryService {

    @Autowired
    private ContentMapper contentMapper;
    @Autowired
    private UserQueryService userQueryService;
    @Autowired
    private ContentInteractionService contentInteractionService;
    @Autowired
    private BrowseHistoryService browseHistoryService;
    @Autowired
    private ContentDetailCacheService contentDetailCacheService;
    @Autowired
    private ContentDetailDataLoader contentDetailDataLoader;
    @Autowired
    private AuthorProfileCache authorProfileCache;

    @Override
    public ContentVO getContentDetail(Long contentId) {
        if (contentId == null) {
            throw new ContentFailedException("contentId不能为空");
        }

        ContentDetailCacheEntry cacheEntry = contentDetailCacheService.getOrLoad(
                contentId,
                () -> contentDetailDataLoader.load(contentId)
        );
        if (cacheEntry == null || cacheEntry.state() == null) {
            throw new ContentFailedException("内容不存在");
        }
        if (cacheEntry.state() == ContentDetailState.NOT_FOUND) {
            throw new ContentFailedException("内容不存在");
        }
        if (cacheEntry.state() == ContentDetailState.DELETED) {
            throw new ContentFailedException("内容已被删除");
        }
        if (cacheEntry.state() == ContentDetailState.NOT_APPROVED) {
            throw new ContentFailedException("内容未通过审核");
        }
        if (cacheEntry.state() == ContentDetailState.INVALID_AUTHOR || cacheEntry.snapshot() == null) {
            throw new ContentFailedException("发布用户信息异常");
        }

        ContentDetailSnapshot snapshot = cacheEntry.snapshot();
        UserAuthInfoVO userInfo = authorProfileCache.get(snapshot.publishUserId());
        if (userInfo == null) {
            userInfo = new UserAuthInfoVO();
        }

        Content viewerState = Content.builder().contentId(snapshot.contentId()).build();
        markLiked(viewerState);
        markCollected(viewerState);
        recordBrowseHistory(contentId);
        return convertDetailSnapshotToVO(snapshot, userInfo, viewerState);
    }

    @Override
    public List<ContentSnapshotVO> getContentSnapshots(Collection<Long> contentIds) {
        if (contentIds == null || contentIds.isEmpty()) {
            return Collections.emptyList();
        }
        List<Long> ids = contentIds.stream()
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (ids.isEmpty()) {
            return Collections.emptyList();
        }
        List<Content> contents = contentMapper.selectBatchIds(ids);
        if (contents == null || contents.isEmpty()) {
            return Collections.emptyList();
        }
        Map<Long, Content> byId = contents.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(Content::getContentId, Function.identity(), (left, right) -> left));
        return ids.stream()
                .map(byId::get)
                .filter(Objects::nonNull)
                .filter(content -> !Integer.valueOf(1).equals(content.getIsDeleted()))
                .filter(content -> AuditStatus.APPROVED.getCode().equals(content.getAuditStatus()))
                .map(this::toSnapshot)
                .toList();
    }

    @Override
    public ContentSnapshotVO getContentSnapshot(Long contentId) {
        Content content = contentMapper.selectById(contentId);
        return content == null ? null : toSnapshot(content);
    }

    @Override
    public List<ContentSnapshotVO> getContentFactSnapshots(Collection<Long> contentIds) {
        if (contentIds == null || contentIds.isEmpty()) {
            return List.of();
        }
        List<Content> contents = contentMapper.selectBatchIds(new ArrayList<>(contentIds));
        return contents == null ? List.of() : contents.stream().map(this::toSnapshot).toList();
    }

    @Override
    public ContentSnapshotVO lockContentSnapshot(Long contentId) {
        Content content = contentMapper.selectByIdForUpdate(contentId);
        return content == null ? null : toSnapshot(content);
    }

    @Override
    public List<ContentSnapshotVO> getContentSnapshotsForReindex(int offset, int limit) {
        List<Content> contents = contentMapper.selectAllForReindex(offset, limit);
        return contents == null ? List.of() : contents.stream().map(this::toSnapshot).toList();
    }

    @Override
    public List<ContentSnapshotVO> getTopLikedContentSnapshots(int limit) {
        List<Content> contents = contentMapper.selectTopLikedContents(limit);
        return contents == null ? List.of() : contents.stream().map(this::toSnapshot).toList();
    }

    @Override
    public Integer countUserPublicContents(Long userId) {
        return contentMapper.countUserPublicContents(userId);
    }

    @Override
    public List<ContentSnapshotVO> getApprovedContentSnapshotsForReindex(int offset, int limit) {
        List<Content> contents = contentMapper.selectApprovedForRecommendWarmup(offset, limit);
        if (contents == null || contents.isEmpty()) {
            return List.of();
        }
        return contents.stream()
                .filter(Objects::nonNull)
                .filter(content -> Integer.valueOf(0).equals(content.getIsDeleted()))
                .filter(content -> AuditStatus.APPROVED.getCode().equals(content.getAuditStatus()))
                .map(this::toSnapshot)
                .toList();
    }

    @Override
    public List<ContentSnapshotVO> getApprovedContentSnapshotsByAuthor(Long publishUserId, int limit) {
        List<Content> contents = contentMapper.selectApprovedByPublishUserId(publishUserId, limit);
        if (contents == null || contents.isEmpty()) {
            return List.of();
        }
        return contents.stream().filter(Objects::nonNull).map(this::toSnapshot).toList();
    }

    @Override
    public List<String> getContentImageUrls(Long contentId) {
        List<ContentImage> images = contentMapper.selectImagesByContentIds(contentId);
        if (images == null || images.isEmpty()) {
            return List.of();
        }
        return images.stream()
                .map(ContentImage::getImageUrl)
                .filter(org.apache.commons.lang3.StringUtils::isNotBlank)
                .toList();
    }

    /** 保留原 Mapper 图片投影的顺序、重复项和空值，不复用审核用的 URL 过滤规则。 */
    @Override
    public List<String> getContentFactImageUrls(Long contentId) {
        List<ContentImage> images = contentMapper.selectImagesByContentIds(contentId);
        if (images == null || images.isEmpty()) {
            return List.of();
        }
        return images.stream().map(ContentImage::getImageUrl)
                .collect(java.util.stream.Collectors.toList());
    }

    private ContentSnapshotVO toSnapshot(Content content) {
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
    public PageVO<ContentVO> getMyContentList(Long userId, Integer current, Integer size, AuditStatus auditStatus) {
        return pageQuery(userId, current, size,
                () -> contentMapper.getMyContentsList(userId, auditStatus == null ? null : auditStatus.getCode()));
    }

    @Override
    public PageVO<ContentVO> getMyLikedContentList(Long userId, Integer current, Integer size) {
        return pageQuery(userId, current, size, () -> contentMapper.getMyLikedContentList(userId));
    }

    @Override
    public PageVO<ContentVO> getMyCollectContentList(Long userId, Integer current, Integer size) {
        return pageQuery(userId, current, size, () -> contentMapper.getMyCollectContentList(userId));
    }

    @Override
    public PageVO<ContentVO> getMyBrowseHistoryContentList(Long userId, Integer current, Integer size) {
        int pageNum = current == null || current <= 0 ? 1 : current;
        int pageSize = size == null || size <= 0 ? 10 : size;
        Page<Long> page = browseHistoryService.pageContentIds(userId, pageNum, pageSize);
        List<Long> ids = page == null || page.getResult() == null ? List.of() : page.getResult();
        if (ids.isEmpty()) {
            return PageVO.<ContentVO>builder()
                    .list(new ArrayList<>())
                    .pageSize(pageSize)
                    .pageNum(pageNum)
                    .totalPage(0)
                    .total(0L)
                    .hasMore(false)
                    .build();
        }
        List<Content> visible = contentMapper.selectBatchIds(ids).stream()
                .filter(content -> Integer.valueOf(0).equals(content.getIsDeleted()))
                .filter(content -> AuditStatus.APPROVED.getCode().equals(content.getAuditStatus()))
                .toList();
        List<ContentVO> voList = assembleContentVOs(visible);
        long total = visible.size();
        int totalPages = (int) Math.ceil(total / (double) pageSize);
        return PageVO.<ContentVO>builder()
                .list(voList)
                .pageSize(pageSize)
                .pageNum(pageNum)
                .totalPage(totalPages)
                .total(total)
                .hasMore(pageNum < totalPages)
                .build();
    }

    @Override
    public PageVO<ContentVO> pageUserPublicContents(Long userId, Integer current, Integer size) {
        return pageQuery(userId, current, size, () -> contentMapper.pageUserPublicContents(userId));
    }

    private PageVO<ContentVO> pageQuery(Long userId, Integer current, Integer size,
                                        java.util.function.Supplier<Page<Content>> query) {
        if (userId == null) {
            throw new ContentFailedException("userId不能为空");
        }
        int pageNum = current == null || current <= 0 ? 1 : current;
        int pageSize = size == null || size <= 0 ? 10 : size;
        PageHelper.startPage(pageNum, pageSize);
        Page<Content> page = query.get();
        List<Content> contentList = page == null || page.getResult() == null
                ? new ArrayList<>() : page.getResult();
        List<ContentVO> voList = assembleContentVOs(contentList);
        long total = page == null ? 0L : page.getTotal();
        int totalPages = (int) Math.ceil(total / (double) pageSize);
        return PageVO.<ContentVO>builder()
                .list(voList)
                .pageSize(pageSize)
                .pageNum(pageNum)
                .totalPage(totalPages)
                .total(total)
                .hasMore(pageNum < totalPages)
                .build();
    }

    private List<ContentVO> assembleContentVOs(List<Content> contents) {
        if (contents == null || contents.isEmpty()) {
            return new ArrayList<>();
        }
        List<Long> userIds = contents.stream()
                .map(Content::getPublishUserId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        List<UserAuthInfoVO> users = userIds.isEmpty()
                ? new ArrayList<>() : userQueryService.getUserAuthInfos(userIds);
        Map<Long, UserAuthInfoVO> userMap = users == null ? Collections.emptyMap() : users.stream()
                .collect(Collectors.toMap(UserAuthInfoVO::getUserId, Function.identity(), (left, right) -> left));
        contents.forEach(this::markLiked);
        contents.forEach(this::markCollected);
        return contents.stream()
                .map(content -> convertContentToVO(content,
                        userMap.getOrDefault(content.getPublishUserId(), new UserAuthInfoVO())))
                .toList();
    }

    private ContentVO convertContentToVO(Content content, UserAuthInfoVO userInfo) {
        return ContentVO.builder()
                .contentId(content.getContentId())
                .contentType(content.getContentType())
                .title(content.getTitle())
                .content(content.getContent())
                .publishUserId(content.getPublishUserId())
                .auditStatus(content.getAuditStatus())
                .createTime(content.getCreateTime())
                .liked(content.getLiked() == null ? 0 : content.getLiked())
                .isLiked(BooleanUtil.isTrue(content.getIsLiked()))
                .isCollected(BooleanUtil.isTrue(content.getIsCollected()))
                .commentCount(content.getCommentCount() == null ? 0 : content.getCommentCount())
                .collectCount(content.getCollectCount() == null ? 0 : content.getCollectCount())
                .avatarUrl(userInfo.getAvatarUrl())
                .nickName(userInfo.getNickName())
                .quantaDepartment(userInfo.getQuantaDepartment())
                .quantaBatch(userInfo.getQuantaBatch())
                .build();
    }

    private ContentVO convertDetailSnapshotToVO(ContentDetailSnapshot snapshot,
                                                 UserAuthInfoVO userInfo,
                                                 Content viewerState) {
        return ContentVO.builder()
                .contentId(snapshot.contentId())
                .contentType(snapshot.contentType())
                .title(snapshot.title())
                .content(snapshot.content())
                .liked(snapshot.liked())
                .commentCount(snapshot.commentCount())
                .collectCount(snapshot.collectCount())
                .publishUserId(snapshot.publishUserId())
                .avatarUrl(userInfo.getAvatarUrl())
                .nickName(userInfo.getNickName())
                .quantaDepartment(userInfo.getQuantaDepartment())
                .quantaBatch(userInfo.getQuantaBatch())
                .auditStatus(snapshot.auditStatus())
                .createTime(snapshot.createTime())
                .images(snapshot.images())
                .isLiked(BooleanUtil.isTrue(viewerState.getIsLiked()))
                .isCollected(BooleanUtil.isTrue(viewerState.getIsCollected()))
                .build();
    }

    private void markLiked(Content content) {
        Long userId = BaseContext.getCurrentId();
        if (userId == null) {
            content.setIsLiked(false);
            return;
        }
        content.setIsLiked(contentInteractionService.isContentLiked(content.getContentId(), userId));
    }

    private void markCollected(Content content) {
        Long userId = BaseContext.getCurrentId();
        if (userId == null) {
            content.setIsCollected(false);
            return;
        }
        content.setIsCollected(contentInteractionService.isContentCollected(content.getContentId(), userId));
    }

    private void recordBrowseHistory(Long contentId) {
        browseHistoryService.recordBrowseHistory(contentId);
    }
}
