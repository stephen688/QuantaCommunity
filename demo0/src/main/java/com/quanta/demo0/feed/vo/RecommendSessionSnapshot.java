package com.quanta.demo0.feed.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 推荐会话的 Redis 持久化快照。
 *
 * <p>快照只保存候选游标和页面内容 ID，不保存带用户状态的 ContentVO；页面重放时
 * 仍需回查当前可见性并重新装配作者、点赞等访问者状态。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RecommendSessionSnapshot {

    /** 会话第一次创建的 epoch milliseconds。 */
    private Long createdAtMs;

    /** 本轮推荐的时间上界，单位为 epoch milliseconds。 */
    private Long startedAt;

    /** 本轮推荐的内容 ID 上界，避免新内容插入历史页。 */
    private Long upperId;

    /** 绑定的内容分类，null 表示全部分类。 */
    private Integer category;

    /** 绑定的页面大小。 */
    private Integer pageSize;

    /** 是否为用户主动再看的新轮次。 */
    private Boolean revisitMode;

    /** 会话内确定性探索抽样种子。 */
    private Long seed;

    /** 已经下发给客户端的帖子 ID，防止同一轮后续页重复。 */
    @Builder.Default
    private Set<Long> deliveredIds = new HashSet<>();

    /** 已召回但尚未组成页面的候选队列。 */
    @Builder.Default
    private java.util.Deque<Long> pendingIds = new ArrayDeque<>();

    /** 当前使用的 Redis 召回窗口索引。 */
    private Integer recallWindowIndex;

    /** 主动再看时引用的已耗尽旧轮次，防止客户端伪造任意历史轮次。 */
    private String revisitOfSessionId;

    /** MySQL 时间键集续扫游标的时间部分，单位为 epoch milliseconds。 */
    private Long dbCursorTime;

    /** MySQL 时间键集续扫游标的 ID 部分。 */
    private Long dbCursorId;

    /** MySQL/候选事实源已经读完。 */
    private Boolean dbExhausted;

    /** 本轮累计下发数量。 */
    private Integer totalDelivered;

    /** 页游标到不可变下发 ID 列表的映射；空字符串代表首屏。 */
    @Builder.Default
    private Map<String, List<Long>> pagesByCursor = new HashMap<>();

    /** 每个已提交页自己的下一页游标，保证旧页重放不读取当前最新游标。 */
    @Builder.Default
    private Map<String, String> pageNextCursors = new HashMap<>();

    /** 每个已提交页的推荐状态快照。 */
    @Builder.Default
    private Map<String, String> pageStates = new HashMap<>();

    /** 每个已提交页的 hasMore 快照。 */
    @Builder.Default
    private Map<String, Boolean> pageHasMore = new HashMap<>();

    /** 每个已提交页的 canRevisit 快照。 */
    @Builder.Default
    private Map<String, Boolean> pageCanRevisit = new HashMap<>();

    /** 是否曾经发现过至少一个当前可见候选，供 EXHAUSTED 页决定再看入口。 */
    private Boolean hadVisibleCandidates;

    /** 已提交页面返回给客户端的下一页游标。 */
    private String currentNextCursor;

    /**
     * 对外部传入的快照做防御性复制，避免提交后调用方继续改变 Redis 序列化内容。
     *
     * @return 可安全持久化的快照副本
     */
    public RecommendSessionSnapshot copy() {
        return RecommendSessionSnapshot.builder()
                .createdAtMs(createdAtMs)
                .startedAt(startedAt)
                .upperId(upperId)
                .category(category)
                .pageSize(pageSize)
                .revisitMode(revisitMode)
                .seed(seed)
                .deliveredIds(deliveredIds == null ? new HashSet<>() : new HashSet<>(deliveredIds))
                .pendingIds(pendingIds == null ? new ArrayDeque<>() : new ArrayDeque<>(pendingIds))
                .recallWindowIndex(recallWindowIndex)
                .revisitOfSessionId(revisitOfSessionId)
                .dbCursorTime(dbCursorTime)
                .dbCursorId(dbCursorId)
                .dbExhausted(dbExhausted)
                .totalDelivered(totalDelivered)
                .pagesByCursor(copyPages(pagesByCursor))
                .pageNextCursors(copyStringMap(pageNextCursors))
                .pageStates(copyStringMap(pageStates))
                .pageHasMore(copyBooleanMap(pageHasMore))
                .pageCanRevisit(copyBooleanMap(pageCanRevisit))
                .hadVisibleCandidates(hadVisibleCandidates)
                .currentNextCursor(currentNextCursor)
                .build();
    }

    private static Map<String, List<Long>> copyPages(Map<String, List<Long>> pages) {
        Map<String, List<Long>> copy = new HashMap<>();
        if (pages != null) {
            pages.forEach((cursor, ids) -> copy.put(
                    cursor == null ? "" : cursor,
                    ids == null ? new ArrayList<>() : new ArrayList<>(ids)));
        }
        return copy;
    }

    private static Map<String, String> copyStringMap(Map<String, String> source) {
        Map<String, String> copy = new HashMap<>();
        if (source != null) {
            source.forEach((cursor, value) -> copy.put(
                    cursor == null ? "" : cursor, value));
        }
        return copy;
    }

    private static Map<String, Boolean> copyBooleanMap(Map<String, Boolean> source) {
        Map<String, Boolean> copy = new HashMap<>();
        if (source != null) {
            source.forEach((cursor, value) -> copy.put(
                    cursor == null ? "" : cursor, value));
        }
        return copy;
    }
}
