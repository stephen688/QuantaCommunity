package com.quanta.demo0.service;

import com.quanta.demo0.entity.Content;

import java.util.List;
import java.util.Map;

/**
 * 用户画像 Hash 读写服务（推荐流个性化 D1/D5）。
 * 职责：画像的写通道（applyBehavior 累加）与读通道（getProfile 供重排 α 计算）。
 * 边界：画像 Hash 是派生快照，事实源在 MySQL 行为数据；不做重建任务（D10）。
 */
public interface UserProfileService {

    /**
     * 累加一次行为：查帖子当前标签集合，对每个标签 HINCRBYFLOAT 权重，
     * 并同步累加 __total。帖子已删除/驳回/不存在时跳过（防脏画像）。
     * Redis 异常向上抛（由调用方决定重试语义），不得吞掉伪装成功。
     */
    void applyBehavior(Long userId, Long contentId, double weight);

    /**
     * 读取用户画像（HGETALL）。无画像/用户不存在返回空 Map（不抛异常）。
     * 返回 Map 含 __total（供 α 计算与画像量评估）。
     */
    Map<String, Double> getProfile(Long userId);

    /** 明确主题偏好独立读取；缺省为空，保持现有实现与调用方兼容。 */
    default Map<String, Double> getExplicitProfile(Long userId) {
        return Map.of();
    }

    /**
     * 帖子标签解析（D5 唯一标签扩展点：LLM 主题标签上线后只改实现这一处）。
     * 画像累加（applyBehavior）与画像流重排（匹配分计算）共用本方法，保证标签口径一致。
     *
     * @return 帖子标签列表（第一版：contentType 1→life / 2→professional；其它返回空列表）
     */
    List<String> resolveContentTags(Content content);
}
