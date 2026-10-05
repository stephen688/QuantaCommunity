package com.quanta.demo0.feed.service.impl;

import com.quanta.demo0.content.vo.ContentSnapshotVO;
import com.quanta.demo0.feed.properties.RecommendProperties;
import com.quanta.demo0.feed.service.UserInterestProfileService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 推荐流探索选择器：按会话累计位置分配近期探索位，并从候选中做确定性、多样性优先的抽样。
 *
 * <p>边界：本类不负责召回、曝光持久化或会话状态推进；调用方必须先按当前数据库事实筛选候选，
 * 并把会话内已经下发的内容从输入候选中排除。固定 seed 保证同一页重试得到同一选择结果。</p>
 */
@Component
@RequiredArgsConstructor
public class RecommendDiscoverySelector {

    private final UserInterestProfileService userInterestProfileService;
    private final RecommendProperties recommendProperties;

    /**
     * 选择一页推荐结果。
     *
     * <p>探索配额按照累计输出位置计算：默认每 5 条最多 1 条。未命中合格探索候选时，
     * 配额归还主推荐，不会在下一页补偿累积。主推荐输入应已按 rankCandidates 的顺序排列。</p>
     *
     * @param userId              当前用户 ID，游客传 null
     * @param ranked              主推荐候选，按 finalScore 降序排列
     * @param discoveryCandidates 最近窗口内的探索候选
     * @param pageSize            本页最大条数
     * @param totalDelivered      本轮会话此前已成功下发条数
     * @param seed                本轮会话稳定随机种子
     * @param startedAt           本轮会话创建时间，作为最近窗口上界
     * @return 按主推荐顺序夹带探索位的去重结果
     */
    public List<ContentSnapshotVO> select(
            Long userId,
            List<ContentSnapshotVO> ranked,
            List<ContentSnapshotVO> discoveryCandidates,
            int pageSize,
            int totalDelivered,
            long seed,
            LocalDateTime startedAt) {
        if (pageSize <= 0 || ranked == null || ranked.isEmpty()) {
            return List.of();
        }

        List<ContentSnapshotVO> primary = distinctValid(ranked);
        if (primary.isEmpty()) {
            return List.of();
        }
        int safeDelivered = Math.max(0, totalDelivered);
        int safePageSize = Math.max(0, pageSize);
        double ratio = normaliseRatio(recommendProperties.getDiscovery().getExplorationRatio());
        List<Integer> explorationSlots = explorationSlots(safeDelivered, safePageSize, ratio);
        if (explorationSlots.isEmpty() || discoveryCandidates == null || discoveryCandidates.isEmpty()) {
            return take(primary, safePageSize);
        }

        // 探索必须引入主排序首屏之外的内容，不能只把原本会展示的主推荐挪到探索位。
        Set<Long> primaryPageIds = take(primary, safePageSize).stream()
                .map(ContentSnapshotVO::getContentId).collect(java.util.stream.Collectors.toSet());
        List<ContentSnapshotVO> outsidePrimaryPage = distinctValid(discoveryCandidates).stream()
                .filter(content -> !primaryPageIds.contains(content.getContentId())).toList();
        List<ContentSnapshotVO> eligible = eligibleDiscoveryCandidates(
                userId, outsidePrimaryPage, startedAt, recommendProperties.getDiscovery().getRecentDays());
        if (eligible.isEmpty()) {
            return take(primary, safePageSize);
        }

        List<ContentSnapshotVO> selected = selectDiverse(eligible, explorationSlots.size(), seed);
        if (selected.isEmpty()) {
            return take(primary, safePageSize);
        }

        Set<Long> selectedIds = selected.stream()
                .map(ContentSnapshotVO::getContentId)
                .collect(java.util.stream.Collectors.toSet());
        List<ContentSnapshotVO> primaryWithoutSelected = primary.stream()
                .filter(content -> !selectedIds.contains(content.getContentId()))
                .toList();

        List<ContentSnapshotVO> result = new ArrayList<>(Math.min(safePageSize, primaryWithoutSelected.size() + selected.size()));
        int primaryIndex = 0;
        int selectedIndex = 0;
        for (int localIndex = 0; localIndex < safePageSize; localIndex++) {
            boolean explorationSlot = selectedIndex < selected.size()
                    && explorationSlots.contains(localIndex);
            if (explorationSlot) {
                result.add(selected.get(selectedIndex++));
            } else if (primaryIndex < primaryWithoutSelected.size()) {
                result.add(primaryWithoutSelected.get(primaryIndex++));
            } else if (selectedIndex < selected.size()) {
                // 主推荐不足页大小时，把有效探索候选追加到页尾，不能制造空位。
                result.add(selected.get(selectedIndex++));
            } else {
                break;
            }
        }
        return result;
    }

    private List<ContentSnapshotVO> distinctValid(List<ContentSnapshotVO> contents) {
        Map<Long, ContentSnapshotVO> byId = new LinkedHashMap<>();
        for (ContentSnapshotVO content : contents) {
            if (content != null && content.getContentId() != null) {
                byId.putIfAbsent(content.getContentId(), content);
            }
        }
        return new ArrayList<>(byId.values());
    }

    private List<ContentSnapshotVO> take(List<ContentSnapshotVO> contents, int pageSize) {
        return new ArrayList<>(contents.subList(0, Math.min(pageSize, contents.size())));
    }

    private List<Integer> explorationSlots(int totalDelivered, int pageSize, double ratio) {
        List<Integer> slots = new ArrayList<>();
        for (int localIndex = 0; localIndex < pageSize; localIndex++) {
            double before = (totalDelivered + localIndex) * ratio;
            double after = (totalDelivered + localIndex + 1) * ratio;
            if (Math.floor(after) > Math.floor(before)) {
                slots.add(localIndex);
            }
        }
        return slots;
    }

    private List<ContentSnapshotVO> eligibleDiscoveryCandidates(
            Long userId,
            List<ContentSnapshotVO> discoveryCandidates,
            LocalDateTime startedAt,
            int recentDays) {
        LocalDateTime upper = startedAt;
        LocalDateTime lower = startedAt == null ? null : startedAt.minusDays(Math.max(1, recentDays));
        List<ContentSnapshotVO> valid = new ArrayList<>();
        for (ContentSnapshotVO content : distinctValid(discoveryCandidates)) {
            if (!isVisible(content) || content.getCreateTime() == null
                    || (upper != null && content.getCreateTime().isAfter(upper))
                    || (lower != null && content.getCreateTime().isBefore(lower))) {
                continue;
            }
            valid.add(content);
        }
        if (valid.isEmpty() || userId == null) {
            return valid;
        }
        Map<String, Double> explicitProfile = userInterestProfileService.getExplicitProfile(userId);
        if (explicitProfile == null || explicitProfile.isEmpty()) {
            return valid;
        }
        List<ContentSnapshotVO> nonNegative = valid.stream()
                .filter(content -> explicitPreferenceScore(content, explicitProfile) >= 0.0)
                .toList();
        // 全部候选都属于厌恶主题时返回空，探索位交还主推荐；负偏好仍可按原排序自然出现。
        return nonNegative;
    }

    private boolean isVisible(ContentSnapshotVO content) {
        return Integer.valueOf(1).equals(content.getAuditStatus())
                && Integer.valueOf(0).equals(content.getIsDeleted());
    }

    private double explicitPreferenceScore(ContentSnapshotVO content, Map<String, Double> explicitProfile) {
        double minimum = 0.0;
        for (String tag : safeTags(content)) {
            Double weight = explicitProfile.get(tag);
            if (weight != null) {
                minimum = Math.min(minimum, weight);
            }
        }
        return minimum;
    }

    private List<ContentSnapshotVO> selectDiverse(List<ContentSnapshotVO> candidates, int limit, long seed) {
        List<ContentSnapshotVO> remaining = new ArrayList<>(candidates);
        List<ContentSnapshotVO> selected = new ArrayList<>(Math.min(limit, candidates.size()));
        Set<Long> authors = new HashSet<>();
        Set<String> tags = new HashSet<>();
        while (!remaining.isEmpty() && selected.size() < limit) {
            ContentSnapshotVO choice = remaining.stream()
                    .max(Comparator
                            .comparingInt((ContentSnapshotVO content) -> diversityScore(content, authors, tags))
                            .thenComparingLong(content -> stableOrder(seed, content.getContentId())))
                    .orElse(null);
            if (choice == null) {
                break;
            }
            selected.add(choice);
            remaining.remove(choice);
            if (choice.getPublishUserId() != null) {
                authors.add(choice.getPublishUserId());
            }
            tags.addAll(safeTags(choice));
        }
        return selected;
    }

    private int diversityScore(ContentSnapshotVO content, Set<Long> authors, Set<String> tags) {
        int score = content.getPublishUserId() == null || !authors.contains(content.getPublishUserId()) ? 2 : 0;
        for (String tag : safeTags(content)) {
            if (!tags.contains(tag)) {
                score++;
            }
        }
        return score;
    }

    private List<String> safeTags(ContentSnapshotVO content) {
        List<String> tags = userInterestProfileService.resolveContentTags(content);
        return tags == null ? List.of() : tags;
    }

    private long stableOrder(long seed, Long contentId) {
        long value = seed ^ (contentId == null ? 0L : contentId * 0x9E3779B97F4A7C15L);
        value ^= value >>> 30;
        value *= 0xBF58476D1CE4E5B9L;
        value ^= value >>> 27;
        value *= 0x94D049BB133111EBL;
        return value ^ (value >>> 31);
    }

    private double normaliseRatio(double ratio) {
        if (Double.isNaN(ratio) || ratio <= 0.0) {
            return 0.0;
        }
        return Math.min(1.0, ratio);
    }
}
