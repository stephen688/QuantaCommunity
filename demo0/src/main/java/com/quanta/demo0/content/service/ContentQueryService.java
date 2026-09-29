package com.quanta.demo0.content.service;

import com.quanta.demo0.content.vo.ContentSnapshotVO;
import com.quanta.demo0.content.vo.ContentVO;
import com.quanta.demo0.platform.common.enums.AuditStatus;
import com.quanta.demo0.platform.common.result.PageVO;

import java.util.Collection;
import java.util.List;

/**
 * 内容域查询服务。
 *
 * <p>这里仅暴露内容查询结果和稳定快照，调用方不需要依赖内容实体。</p>
 */
public interface ContentQueryService {

    ContentVO getContentDetail(Long contentId);

    List<ContentSnapshotVO> getContentSnapshots(Collection<Long> contentIds);

    List<ContentSnapshotVO> getApprovedContentSnapshotsByAuthor(Long publishUserId, int limit);

    List<String> getContentImageUrls(Long contentId);

    PageVO<ContentVO> getMyContentList(Long userId, Integer current, Integer size, AuditStatus auditStatus);

    PageVO<ContentVO> getMyLikedContentList(Long userId, Integer current, Integer size);

    PageVO<ContentVO> getMyCollectContentList(Long userId, Integer current, Integer size);

    PageVO<ContentVO> getMyBrowseHistoryContentList(Long userId, Integer current, Integer size);

    PageVO<ContentVO> pageUserPublicContents(Long userId, Integer current, Integer size);
}
