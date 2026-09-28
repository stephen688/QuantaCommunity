package com.quanta.demo0.feed.utils;

import com.quanta.demo0.content.entity.Content;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 帖子热度分静态计算工具（推荐流个性化 03 Task 3.2 从 HotScore 消费链路提取）。
 * 职责：热度公式的唯一真源——(点赞×3 + 评论×2 + 收藏×5) / (发布小时数+2)^1.5，
 * 无互动内容给 20 分保底分避免完全沉底。
 * 边界：纯函数（输入 Content 计数与发布时间，输出分数，无状态无副作用）；
 * 热度池 ZSET 写入（ContentServiceImpl / ContentExposureServiceImpl）与画像流重排归一化
 * 均引用本方法，严禁在别处复制公式形成第二真源。
 */
public final class HotScoreCalculator {

    private HotScoreCalculator() {
        // 静态工具类禁止实例化
    }

    /**
     * 计算帖子热度分（Hacker News 算法变种）。
     * 公式：hotScore = (liked×3 + commentCount×2 + collectCount×5) / (hours + 2)^1.5；
     * 基础分为 0 时改用 20 分保底（新帖冷启动不完全沉底）。
     *
     * @param content 帖子实体（liked/commentCount/collectCount 为 null 按 0，createTime 为 null 按当前时间）
     * @return 热度分（恒为正数：保底分机制保证）
     */
    public static double calculate(Content content) {
        // 1. 获取互动数据（null 安全处理）
        int liked = content.getLiked() == null ? 0 : content.getLiked();
        int commentCount = content.getCommentCount() == null ? 0 : content.getCommentCount();
        int collectCount = content.getCollectCount() == null ? 0 : content.getCollectCount();

        // 2. 基础分（加权求和：收藏认可度最高，其次点赞、评论）
        double baseScore = liked * 3 + commentCount * 2 + collectCount * 5;

        // 3. 时间衰减因子（新内容衰减慢，旧内容衰减快）
        LocalDateTime createTime = content.getCreateTime() != null ? content.getCreateTime() : LocalDateTime.now();
        long hours = Duration.between(createTime, LocalDateTime.now()).toHours();
        double timeDecay = Math.pow(hours + 2, 1.5);

        // 4. 热度分
        double hotScore = baseScore / timeDecay;

        // 5. 保底分：无互动内容给 20 分基础分（避免完全沉底）
        if (baseScore == 0) {
            hotScore = 20.0 / timeDecay;
        }
        return hotScore;
    }
}
