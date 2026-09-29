package com.quanta.demo0.content.service;

import com.quanta.demo0.content.vo.ContentSnapshotVO;

/**
 * 内容域提供给互动域的同步计数端口。
 */
public interface ContentCounterService {

    ContentSnapshotVO getContentSnapshot(Long contentId);

    int changeLikedCount(Long contentId, int delta);

    int changeCollectCount(Long contentId, int delta);

    /** 调整内容的可见评论数；与评论主事务同步提交。 */
    int changeCommentCount(Long contentId, int delta);
}
