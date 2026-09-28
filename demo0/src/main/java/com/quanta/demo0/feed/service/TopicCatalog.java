package com.quanta.demo0.feed.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 推荐主题受控词表。
 *
 * 职责：从 classpath 唯一资源加载主题、把模型/存储输入归一化为稳定 ID；
 * 边界：只做纯内存解析，不调用模型、数据库或网络。
 */
public final class TopicCatalog {

    private static final String RESOURCE = "recommend-topics.json";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final List<Topic> TOPICS = loadTopics();
    private static final Map<String, String> TOKEN_TO_ID = buildTokenIndex(TOPICS);

    private TopicCatalog() {
    }

    /**
     * 返回不可变的完整受控词表。
     */
    public static List<Topic> topics() {
        return TOPICS;
    }

    /**
     * 解析已存储的 JSON 标签数组，只保留有效主题 ID 并去重。
     * 非法 JSON、非数组或包含非字符串元素时返回空列表；存储读取不会抛出解析异常。
     */
    public static List<String> parseStoredTags(String storedTags) {
        if (storedTags == null || storedTags.isBlank()) {
            return List.of();
        }
        try {
            JsonNode node = OBJECT_MAPPER.readTree(storedTags);
            if (node == null || !node.isArray()) {
                return List.of();
            }
            List<String> raw = new ArrayList<>();
            for (JsonNode item : node) {
                if (!item.isTextual()) {
                    return List.of();
                }
                raw.add(item.textValue());
            }
            return normalizeTopics(raw);
        } catch (IOException | RuntimeException ignored) {
            return List.of();
        }
    }

    /**
     * 将主题 ID、中文标签或别名归一化为受控 ID，保持首次出现顺序并去重。
     */
    public static List<String> normalizeTopics(List<String> rawTopics) {
        if (rawTopics == null || rawTopics.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String rawTopic : rawTopics) {
            if (rawTopic == null || rawTopic.isBlank()) {
                continue;
            }
            String topicId = TOKEN_TO_ID.get(normalizeToken(rawTopic));
            if (topicId != null) {
                normalized.add(topicId);
            }
        }
        return List.copyOf(normalized);
    }

    private static List<Topic> loadTopics() {
        ClassPathResource resource = new ClassPathResource(RESOURCE);
        try (InputStream inputStream = resource.getInputStream()) {
            List<Topic> topics = OBJECT_MAPPER.readValue(
                    inputStream,
                    new TypeReference<List<Topic>>() {
                    });
            if (topics == null || topics.isEmpty()) {
                throw new IllegalStateException("主题词表不能为空");
            }
            LinkedHashSet<String> ids = new LinkedHashSet<>();
            for (Topic topic : topics) {
                if (topic == null || topic.id() == null || topic.id().isBlank()
                        || !ids.add(topic.id())) {
                    throw new IllegalStateException("主题词表存在重复或空 ID");
                }
            }
            return Collections.unmodifiableList(new ArrayList<>(topics));
        } catch (IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static Map<String, String> buildTokenIndex(List<Topic> topics) {
        Map<String, String> index = new LinkedHashMap<>();
        for (Topic topic : topics) {
            putToken(index, topic.id(), topic.id());
            putToken(index, topic.label(), topic.id());
            if (topic.aliases() != null) {
                for (String alias : topic.aliases()) {
                    putToken(index, alias, topic.id());
                }
            }
        }
        return Collections.unmodifiableMap(index);
    }

    private static void putToken(Map<String, String> index, String token, String topicId) {
        if (token == null || token.isBlank()) {
            return;
        }
        String normalizedToken = normalizeToken(token);
        String previous = index.putIfAbsent(normalizedToken, topicId);
        if (previous != null && !previous.equals(topicId)) {
            throw new ExceptionInInitializerError("主题词表别名冲突：" + token);
        }
    }

    private static String normalizeToken(String token) {
        return token.trim().toLowerCase(Locale.ROOT);
    }

    /** 受控主题的稳定 ID、展示标签和兼容别名。 */
    public record Topic(String id, String label, List<String> aliases) {
        public Topic {
            aliases = aliases == null ? List.of() : List.copyOf(aliases);
        }
    }
}
