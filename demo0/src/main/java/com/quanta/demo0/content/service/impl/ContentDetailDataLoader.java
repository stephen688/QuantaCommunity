package com.quanta.demo0.content.service.impl;

import com.quanta.demo0.content.entity.Content;
import com.quanta.demo0.content.entity.ContentImage;
import com.quanta.demo0.content.enums.ContentDetailState;
import com.quanta.demo0.content.mapper.ContentMapper;
import com.quanta.demo0.content.vo.ContentDetailCacheEntry;
import com.quanta.demo0.content.vo.ContentDetailSnapshot;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 从 MySQL 和图片表构造可共享的帖子详情快照。
 *
 * ============================================================
 * 【快照里有什么、没什么 —— 比"缓存了哪些字段"更重要】
 * ============================================================
 * 进快照的：内容本身、图片列表、作者 id、三个计数 —— **与访问者无关**的稳定数据，
 * 所以所有用户能共享同一份缓存条目（这也是两级缓存能成立的前提）。
 *
 * 不进快照的：我是否点过赞/收藏、是否高亮 —— 这些 per-visitor 状态
 * 由上层在命中缓存后单独处理（每次仍要查，但不拖累共享缓存）。
 * **判断标准：两个用户请求这个接口，这个字段会不同吗？会 → 不能进共享缓存。**
 *
 * 【为什么返回四种"未命中"状态而不是抛异常？】
 * NOT_FOUND / DELETED / NOT_APPROVED / INVALID_AUTHOR 都会被上层当**负结果**
 * 缓存起来挡穿透。抛异常的话，"帖子不存在"这种常态请求就得走异常通道，
 * 日志会被刷爆，还拿不到可缓存的负结果 —— **把业务上的"未命中"和数据访问的"出错"分开**。
 */
@Component
public class ContentDetailDataLoader {

    private static final int APPROVED_STATUS = 1;

    private final ContentMapper contentMapper;

    public ContentDetailDataLoader(ContentMapper contentMapper) {
        this.contentMapper = contentMapper;
    }

    /**
     * 回源加载：把 DB 里的行翻译成"可缓存的查询结论"。
     *
     * 【状态判断的顺序有讲究】不存在 → 已删 → 未过审 → 作者缺失，
     * 从最客观到最贴近业务逐层排除；每个分支返回**独立的负状态**，
     * 上层（ContentQueryServiceImpl）据此给出不同的提示文案，
     * 缓存侧（ContentDetailCacheServiceImpl.ttlSeconds）统一按"负结果短 TTL"处理 ——
     * 状态分得开，提示和缓存策略才各管各的。
     *
     * 【图片为什么二次查询而不是 JOIN】这里是详情缓存最热的回源路径：
     * 主行存在才查图片，被穿透的请求（查无此帖）在前面分支就返回了，
     * 连图片查询都省掉；两条简单 SQL 各自命中主键/索引，也比一条 JOIN 好解释、好调优。
     */
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
        // 查询图片列表
        List<ContentImage> contentImages = contentMapper.selectImagesByContentIds(contentId);
        List<String> imageUrls = contentImages == null
                ? List.of()
                : contentImages.stream()
                .map(ContentImage::getImageUrl)
                .filter(StringUtils::isNotBlank)
                .toList();
        // 构造快照
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

    /** 计数列为 null（历史数据/默认空）时按 0 出：快照进 JSON 后前端不该拿到 null 做运算。 */
    private int zeroIfNull(Integer value) {
        return value == null ? 0 : value;
    }
}
