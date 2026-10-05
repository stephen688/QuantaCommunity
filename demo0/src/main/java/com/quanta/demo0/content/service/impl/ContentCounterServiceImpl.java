package com.quanta.demo0.content.service.impl;

import com.quanta.demo0.content.entity.Content;
import com.quanta.demo0.content.service.ContentCounterService;
import com.quanta.demo0.content.vo.ContentSnapshotVO;
import com.quanta.demo0.content.mapper.ContentMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 内容域同步计数和轻量事实查询；不依赖互动实现，避免写路径循环注入。 */

/**
 * 教学补充（接上句，讲清这个"薄壳类"背后的三个设计决策）：
 *
 * ============================================================
 * 【计数为什么走 "UPDATE ... SET liked = liked + ?" 而不是先查再改？】
 * ============================================================
 * changeLikedCount 传的是 delta（+1/-1），SQL 是 `SET liked = GREATEST(0, liked + #{delta})`
 * （见 ContentMapper.updateLiked）：
 *   - 原子：UPDATE 行锁保证并发下不丢更新 —— 若先 SELECT 拿旧值、Java 里 +1 再 UPDATE，
 *     两个并发点赞会互相覆盖（丢失更新），计数漂移就是这么来的；
 *   - GREATEST(0, ...) 行内兜底：取消点赞把计数打到 0 以下时按 0 截断，脏数据不外泄；
 *   - 返回是否命中行：0 行说明帖子不存在，调用方据此决定回滚。
 *
 * 【这个类存在的意义：解耦，不是转发】
 * 它只是把 ContentMapper 包了一层，为什么 interaction 域要绕这里而不是直接用 ContentMapper？
 * —— 按域分包的边界规则：**跨域只能调对方的公开 Service，不能摸对方的 Mapper/Entity**。
 * 同时这里也是防循环依赖的阀门：Content 域依赖 interaction 的 Service，
 * interaction 反过来要改 content 计数，若互相注入 Impl 就成环 ——
 * 都只依赖对方**域接口**（ContentCounterService / ContentInteractionService），
 * Spring 按接口注入，环就断在这层抽象上。
 *
 * 【面试追问：计数能不能放 Redis，异步刷回 MySQL？】
 * 能（incr 后定时刷回），但本项目的点赞幂等方案依赖"insert 明细受影响行数"做判定，
 * 计数与明细在**同一个 DB 事务**里才不会漂 —— 小规模社区，DB 计数够用，
 * Redis 计数是"大 V 帖子百万并发点赞"场景的方案。**选型跟着量级走，不跟着炫技走。**
 */
@Service
@RequiredArgsConstructor
public class ContentCounterServiceImpl implements ContentCounterService {

    private final ContentMapper contentMapper;

    /**
     * 跨域透传：把内容行打包成"事实快照"（不做可见性过滤、不走详情缓存）。
     * 典型调用：interaction 域点赞前确认帖子存在、计数变更后回读最新值 ——
     * 这类场景要的是**当下的事实**，缓存投影反而帮倒忙。
     */
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

    /** 点赞数增减（delta=+1/-1）：Mapper 内原子自增 + GREATEST 防负，返回 1/0 表示是否命中行。 */
    @Override
    public int changeLikedCount(Long contentId, int delta) {
        return contentMapper.updateLiked(contentId, delta) ? 1 : 0;
    }

    /** 收藏数增减：语义同 changeLikedCount，只是列换成 collect_count（见 ContentMapper.updateCollectCount）。 */
    @Override
    public int changeCollectCount(Long contentId, int delta) {
        return contentMapper.updateCollectCount(contentId, delta);
    }

    /** 评论数增减：语义同上（见 ContentMapper.updateCommentCount）。 */
    @Override
    public int changeCommentCount(Long contentId, int delta) {
        return contentMapper.updateCommentCount(contentId, delta);
    }
}
