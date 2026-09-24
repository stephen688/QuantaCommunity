package com.quanta.demo0.service.Impl;

import com.quanta.demo0.constant.RedisConstants;
import com.quanta.demo0.entity.Content;
import com.quanta.demo0.enums.AuditStatus;
import com.quanta.demo0.mapper.ContentMapper;
import com.quanta.demo0.service.UserProfileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 用户画像 Hash 读写服务实现（推荐流个性化 D1/D5/D10）。
 * 职责：按行为权重累加 user:profile:{userId} 的标签 field 与 __total；
 * 读取画像供推荐重排层做 α 动态调整。
 * 边界：MySQL 是事实源——累加前校验帖子当前审核/删除状态，非法帖跳过不抛异常；
 * 画像 Hash 不设 TTL（D10：不做重建，丢失后重新积累）；
 * Redis 异常向上抛，由调用方（02 的画像消费者）决定重试语义。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserProfileServiceImpl implements UserProfileService {

    /** 内容 Mapper：查帖子当前状态（事实源校验，防脏画像） */
    private final ContentMapper contentMapper;

    /** Redis 操作模板：画像 Hash 读写 */
    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 累加一次行为：查帖子当前标签集合，对每个标签 HINCRBYFLOAT 权重，
     * 并同步累加 __total。帖子已删除/驳回/不存在时跳过（防脏画像）。
     * Redis 异常向上抛（由调用方决定重试语义），不得吞掉伪装成功。
     */
    @Override
    public void applyBehavior(Long userId, Long contentId, double weight) {
        // 1. 查帖子当前状态：MySQL 是事实源，画像累加前必须校验
        Content content = contentMapper.selectById(contentId);
        if (content == null
                || !AuditStatus.APPROVED.getCode().equals(content.getAuditStatus())
                || !Integer.valueOf(0).equals(content.getIsDeleted())) {
            // 跳过不抛异常：帖子状态不会因消费重试变合法，重试无意义
            log.info("跳过画像累加，帖子状态不合法或不存在，userId={}, contentId={}", userId, contentId);
            return;
        }

        // 2. 解析帖子标签（D5 唯一扩展点：LLM 标签上线后只改 resolveContentTags）
        List<String> tags = resolveContentTags(content);
        if (tags.isEmpty()) {
            log.info("跳过画像累加，帖子无有效标签，userId={}, contentId={}, contentType={}",
                    userId, contentId, content.getContentType());
            return;
        }

        // 3. 逐标签累加权重（HINCRBYFLOAT），不设 Hash TTL
        String profileKey = RedisConstants.USER_PROFILE_KEY + userId;
        for (String tag : tags) {
            stringRedisTemplate.opsForHash().increment(profileKey, tag, weight);
        }
        // 4. __total 同步累加，作为 α 动态调整的数据源
        stringRedisTemplate.opsForHash().increment(
                profileKey, RedisConstants.USER_PROFILE_TOTAL_FIELD, weight);
    }

    /**
     * 读取用户画像（HGETALL）。无画像/用户不存在返回空 Map（不抛异常）。
     * 返回 Map 含 __total（供 α 计算与画像量评估）。
     */
    @Override
    public Map<String, Double> getProfile(Long userId) {
        // null userId 防御：匿名/异常调用直接返回空画像，不触碰 Redis
        if (userId == null) {
            return Collections.emptyMap();
        }

        Map<Object, Object> rawEntries = stringRedisTemplate.opsForHash()
                .entries(RedisConstants.USER_PROFILE_KEY + userId);
        if (rawEntries == null || rawEntries.isEmpty()) {
            return Collections.emptyMap();
        }

        // HINCRBYFLOAT 的 value 存储为字符串，转回 Double 供重排层计算
        Map<String, Double> profile = new HashMap<>();
        for (Map.Entry<Object, Object> entry : rawEntries.entrySet()) {
            profile.put(
                    String.valueOf(entry.getKey()),
                    Double.parseDouble(String.valueOf(entry.getValue())));
        }
        return profile;
    }

    /**
     * 帖子标签解析（D5：第一版只用 contentType 维度，唯一标签扩展点）。
     * contentType：1-生活求助 → life；2-专业问答 → professional；
     * 其它值返回空列表（由调用方跳过，不抛异常）。
     * LLM 主题标签上线后只需扩展此方法，画像 field 与重排匹配分自动同步生长
     * （画像累加与 03 Task 3.2 重排共用本方法，禁止另写标签口径）。
     */
    @Override
    public List<String> resolveContentTags(Content content) {
        if (content.getContentType() == null) {
            return List.of();
        }
        if (content.getContentType() == 1) {
            return List.of("life");
        }
        if (content.getContentType() == 2) {
            return List.of("professional");
        }
        return List.of();
    }
}
