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

    /** 查询当前数据库事实，不过滤审核态，供写入校验和索引校准使用。 */
    ContentSnapshotVO getContentSnapshot(Long contentId);

    /** 保留原 selectBatchIds 的已审核、未删除及正文摘要查询语义。 */
    List<ContentSnapshotVO> getContentFactSnapshots(Collection<Long> contentIds);

    /** 在调用者事务中锁定内容事实，供回答采纳串行化使用。 */
    ContentSnapshotVO lockContentSnapshot(Long contentId);

    /** 分页返回所有状态的内容事实，供派生索引 upsert/delete 重建使用。 */
    List<ContentSnapshotVO> getContentSnapshotsForReindex(int offset, int limit);

    /** 返回已审核内容的点赞排序候选。 */
    List<ContentSnapshotVO> getTopLikedContentSnapshots(int limit);

    /** 统计用户公开内容数量。 */
    Integer countUserPublicContents(Long userId);

    /**
     * 分页查询已审核内容的稳定快照，供 RAG 全量重建使用。
     *
     * @param offset 偏移量
     * @param limit  批次大小
     * @return 已审核且未删除的内容快照
     */
    List<ContentSnapshotVO> getApprovedContentSnapshotsForReindex(int offset, int limit);

    List<ContentSnapshotVO> getApprovedContentSnapshotsByAuthor(Long publishUserId, int limit);

    List<String> getContentImageUrls(Long contentId);

    /** 返回图片事实序列，不过滤空 URL，供保留原列表装配契约的读取方使用。 */
    List<String> getContentFactImageUrls(Long contentId);

    PageVO<ContentVO> getMyContentList(Long userId, Integer current, Integer size, AuditStatus auditStatus);

    PageVO<ContentVO> getMyLikedContentList(Long userId, Integer current, Integer size);

    PageVO<ContentVO> getMyCollectContentList(Long userId, Integer current, Integer size);

    PageVO<ContentVO> getMyBrowseHistoryContentList(Long userId, Integer current, Integer size);

    PageVO<ContentVO> pageUserPublicContents(Long userId, Integer current, Integer size);
}
