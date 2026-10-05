package com.quanta.demo0.content.service;

import com.quanta.demo0.content.vo.ContentSnapshotVO;

/**
 * 内容域提供给互动域的同步计数端口。
 *
 * ============================================================
 * 【端口语义：跨域 + 同步 + 同事务】
 * ============================================================
 * 给谁用：互动域（点赞/收藏/评论）在**自己的事务里**调用——计数变更与互动明细
 * 在同一个 DB 事务提交，两边永不漂移（不放 Redis 异步计数的原因见 ContentCounterServiceImpl）。
 * 【为什么互动域不直接用 ContentMapper】跨域只能依赖对方的公开 Service 接口，
 * 不能摸对方的 Mapper/Entity——这层接口同时是防循环依赖的阀门（见实现类注释）。
 * 【返回值约定】change* 系列返回受影响行数：1 = 成功，0 = 内容不存在（或并发被删），
 * 调用方应据此回滚自己的事务——**0 不是"忽略"，是"这次互动不成立"**。
 */
public interface ContentCounterService {

    /** 内容事实快照（同 ContentQueryService.getContentSnapshot 的语义，让互动域免于跨域依赖查询服务）。 */
    ContentSnapshotVO getContentSnapshot(Long contentId);

    /** 原子增减点赞数（delta 可正可负），返回受影响行数（0 = 内容不存在，调用方应回滚）。 */
    int changeLikedCount(Long contentId, int delta);

    /** 原子增减收藏数，语义同 changeLikedCount。 */
    int changeCollectCount(Long contentId, int delta);

    /** 调整内容的可见评论数；与评论主事务同步提交。 */
    // 【为什么"可见评论数"由评论域触发维护】删评/审评会减计数，"可见"口径只有评论域清楚，
    // content 域不越权解释——计数的业务口径归触发变更的那一方。
    int changeCommentCount(Long contentId, int delta);
}
