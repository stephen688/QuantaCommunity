package com.quanta.demo0.feed.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quanta.demo0.platform.redis.constant.RedisConstants;
import com.quanta.demo0.feed.dto.BotProfileEventDTO;
import com.quanta.demo0.feed.entity.UserProfileSignal;
import com.quanta.demo0.content.exception.ContentFailedException;
import com.quanta.demo0.feed.mapper.UserProfileSignalMapper;
import com.quanta.demo0.feed.properties.RecommendProperties;
import com.quanta.demo0.feed.service.ExplicitPreferenceService;
import com.quanta.demo0.feed.mq.producer.FeedEventProducer;
import com.quanta.demo0.feed.service.TopicCatalog;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/** 显式偏好服务：追加事实与 Outbox 同事务；按最新记忆状态覆盖画像，不与行为累计/衰减混用。 */
@Service
@RequiredArgsConstructor
public class ExplicitPreferenceServiceImpl implements ExplicitPreferenceService {
    private final UserProfileSignalMapper mapper;
    private final FeedEventProducer feedEventProducer;
    private final ObjectMapper objectMapper;
    private final StringRedisTemplate redis;
    private final RecommendProperties properties;
    /** 快照代数守卫：慢消费者的旧快照不能覆盖新状态，整 Hash 替换原子完成。 */
    private static final DefaultRedisScript<Long> REPLACE = new DefaultRedisScript<>("""
            local previous = tonumber(redis.call('HGET',KEYS[1],'__version') or '-1')
            if previous > tonumber(ARGV[1]) then return 0 end
            redis.call('DEL',KEYS[1])
            redis.call('HSET',KEYS[1],'__version',ARGV[1])
            for i=2,#ARGV,2 do redis.call('HSET',KEYS[1],ARGV[i],ARGV[i+1]) end
            return 1
            """, Long.class);

    @Override
    @Transactional
    public boolean accept(BotProfileEventDTO event) {
        // ① 显式验证：后台入口也不依赖 Controller 的 Bean Validation。
        UserProfileSignal signal = normalize(event);
        UserProfileSignal existing = mapper.findEvent(signal.getEventId());
        if (existing != null) {
            requireSameEvent(existing, signal);
            return false;
        }
        // ② 锁同用户提交顺序；重复 HTTP 在锁后再次检查，失败必须回滚事实与事件。
        if (!Integer.valueOf(0).equals(mapper.lockUser(signal.getUserId()))) {
            throw new ContentFailedException("偏好目标用户不存在、已删除或已封禁");
        }
        existing = mapper.findEvent(signal.getEventId());
        if (existing != null) {
            requireSameEvent(existing, signal);
            return false;
        }
        if (mapper.insert(signal) != 1) {
            throw new IllegalStateException("显式偏好事实写入失败");
        }
        feedEventProducer.createProfileUpdatedEvent(signal.getUserId(), signal.getEventId());
        return true;
    }

    @Override
    @Transactional
    public void reconcile(Long userId) {
        // ① 重新读取事实而不是消息增量，删除先到、旧 UPSERT 后到也不会复活。
        Integer status = mapper.lockUser(userId);
        long generation = Optional.ofNullable(mapper.generation(userId)).orElse(0L);
        Map<String, Double> scores = new LinkedHashMap<>();
        if (Integer.valueOf(0).equals(status)) {
            for (UserProfileSignal signal : mapper.latestStates(userId)) {
                if (!"UPSERT".equals(signal.getOperation())) {
                    continue;
                }
                double score = "negative".equals(signal.getValence())
                        ? -properties.getProfile().getExplicitNegativeWeight()
                        : properties.getProfile().getExplicitPositiveWeight();
                // 每个主题以最新有效的明确表达为准，不随消息数重复累加。
                for (String topic : TopicCatalog.parseStoredTags(signal.getTopics())) {
                    scores.put(topic, score);
                }
            }
        }
        // ② 整体覆盖：版本和字段同一个 Lua 操作，Redis 故障向 Inbox 抛出。
        List<String> arguments = new ArrayList<>();
        arguments.add(Long.toString(generation));
        scores.forEach((topic, score) -> { arguments.add(topic); arguments.add(score.toString()); });
        Long result = redis.execute(REPLACE, List.of(RedisConstants.USER_PROFILE_EXPLICIT_KEY + userId), arguments.toArray());
        if (result == null) {
            throw new IllegalStateException("显式画像快照更新没有结果");
        }
    }

    private UserProfileSignal normalize(BotProfileEventDTO event) {
        if (event == null || event.getUserId() == null || event.getUserId() <= 0
                || event.getRevision() == null || event.getRevision() <= 0
                || event.getEventId() == null || !event.getEventId().matches("[0-9a-fA-F-]{32,36}")
                || event.getMemoryId() == null || !event.getMemoryId().matches("[0-9a-fA-F-]{32,36}")
                || event.getPersonaVersion() == null || event.getPersonaVersion().isBlank()
                || event.getPersonaVersion().length() > 64 || event.getTopics() == null
                || event.getTopics().size() > 3) {
            throw new ContentFailedException("显式偏好事件字段非法");
        }
        List<String> topics = TopicCatalog.normalizeTopics(event.getTopics());
        if (topics.size() != new HashSet<>(event.getTopics()).size()) {
            throw new ContentFailedException("显式偏好含未知主题");
        }
        if ("DELETE".equals(event.getOperation())) {
            if (!topics.isEmpty() || event.getValence() != null) {
                throw new ContentFailedException("撤销事件不得包含主题或极性");
            }
        } else if (!"UPSERT".equals(event.getOperation()) || topics.isEmpty()
                || !("positive".equals(event.getValence()) || "negative".equals(event.getValence()))) {
            throw new ContentFailedException("显式偏好操作或极性非法");
        }
        List<String> canonicalTopics = topics.stream().sorted().toList();
        try {
            return UserProfileSignal.builder().eventId(event.getEventId()).userId(event.getUserId())
                    .memoryId(event.getMemoryId()).personaVersion(event.getPersonaVersion())
                    .revision(event.getRevision()).operation(event.getOperation())
                    .topics(objectMapper.writeValueAsString(canonicalTopics)).valence(event.getValence()).build();
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("受控主题序列化失败", exception);
        }
    }

    private void requireSameEvent(UserProfileSignal existing, UserProfileSignal incoming) {
        if (!Objects.equals(existing.getUserId(), incoming.getUserId())
                || !Objects.equals(existing.getMemoryId(), incoming.getMemoryId())
                || !Objects.equals(existing.getPersonaVersion(), incoming.getPersonaVersion())
                || !Objects.equals(existing.getRevision(), incoming.getRevision())
                || !Objects.equals(existing.getOperation(), incoming.getOperation())
                || !Objects.equals(TopicCatalog.parseStoredTags(existing.getTopics()), TopicCatalog.parseStoredTags(incoming.getTopics()))
                || !Objects.equals(existing.getValence(), incoming.getValence())) {
            throw new ContentFailedException("同一 eventId 的偏好内容不一致");
        }
    }
}
