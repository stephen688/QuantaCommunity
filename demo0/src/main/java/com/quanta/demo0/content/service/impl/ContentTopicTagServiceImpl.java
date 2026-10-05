package com.quanta.demo0.content.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quanta.demo0.content.entity.Content;
import com.quanta.demo0.platform.common.enums.AuditStatus;
import com.quanta.demo0.content.mapper.ContentMapper;
import com.quanta.demo0.content.properties.ContentTopicProperties;
import com.quanta.demo0.content.service.ContentTopicTagService;
import com.quanta.demo0.content.mq.producer.ContentEventProducer;
import com.quanta.demo0.feed.service.TopicCatalog;
import com.quanta.demo0.platform.web.trace.TraceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 内容主题标签服务实现。
 *
 * 职责：调用现有 ChatModel、校验受控词表并条件写入 Content.tags；
 * 边界：模型调用发生在消息消费事务之外，写库只接受审核通过且 tags 为 NULL 的帖子。
 *
 * ============================================================
 * 【LLM 调用嵌进业务链路的三条纪律，这个类是活教材】
 * ============================================================
 * 1. **受控词表**：不让模型自由发挥 —— prompt 里只给 TopicCatalog 词表，
 *    返回后再经 normalizeTopics 校验，不在词表里直接抛异常。
 *    为什么这么狠：标签的下游是画像重排（β·标签匹配分），
 *    一个脏标签就污染一批用户的推荐 —— **LLM 输出当不可信输入处理，同外部 API**。
 *
 * 2. **NULL 与 [] 语义不同**（见 tagContent 里的判断）：
 *    tags=NULL = 还没处理过（可重试）；tags=[] = 处理过但没有命中主题（终态）。
 *    如果把"无命中"也存 NULL，重试消息会反复打模型 —— 白烧钱还可能翻出不同结果。
 *    **用数据本身的状态表达"处理进度"，不要另建状态表**。
 *
 * 3. **超时用 future.get(timeout) 而不是改 HTTP client 超时**：
 *    supplyAsync 包一层再限时，是因为 ChatModel 的超时配置未必暴露/生效，
 *    在调用方兜底保证消费线程不被模型卡死 —— MQ 消费者线程被占满 = 整条队列堵塞。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContentTopicTagServiceImpl implements ContentTopicTagService {

    private final ContentMapper contentMapper;
    private final ContentEventProducer contentEventProducer;
    private final ChatModel chatModel;
    private final ObjectMapper objectMapper;
    private final ContentTopicProperties properties;

    /**
     * 打标主流程（由 Consumer 在事务外调用）。
     *
     * 【最后的并发保险：条件写入】updateTags 只在 tags IS NULL 时生效
     * （WHERE 带条件），返回 0 行说明并发有别的任务已写入 —— 再查一次确认后放行。
     * 与审核域的 updateAuditStatusIfPending、互动域的 insert 受影响行数，
     * 是同一个 CAS 思想的三处落地。
     *
     * 【不可见为什么抛异常而不是 return？】
     * return = 消费成功 = Inbox 标 SUCCESS，消息就死了；
     * 抛异常 = 走重试链路，帖子将来审核恢复时还能补标。
     * **"现在做不了"和"做不了"是两种错误处理**。
     */
    @Override
    public void tagContent(Long contentId) {
        if (!properties.isEnabled() || contentId == null) {
            return;
        }

        Content content = contentMapper.selectById(contentId);
        if (!isVisible(content)) {
            // 不假记 SUCCESS：恢复审核通过后可重试；最终 DEAD 沿用已有运营重放。
            throw new IllegalStateException("主题标签内容当前不可见或不存在");
        }

        // NULL 是未处理标记；[] 也表示已处理，不能因空结果重复消耗模型费用。
        if (content.getTags() != null) {
            log.info("跳过主题标签，帖子已经处理，contentId={}", contentId);
            return;
        }

        List<String> topics = parseModelTopics(callModel(buildPrompt(content)));
        String storedTags = writeJson(topics);
        int updatedRows = contentMapper.updateTags(contentId, storedTags);
        if (updatedRows != 1) {
            Content current = contentMapper.selectById(contentId);
            if (current == null || current.getTags() == null) {
                throw new IllegalStateException("主题标签条件写入未完成，需要重试或补偿");
            }
            log.info("主题标签已由其它任务处理，contentId={}", contentId);
        }
    }

    /**
     * 存量回填（管理端 ContentTopicTagBackfillController 调用）：以 contentId 为游标，
     * 把"已过审但 tags 还是 NULL"的帖子批量登记成 Outbox 打标事件。
     *
     * 【为什么这里只派活、不碰 ChatModel？】
     * 回填入口是 HTTP 接口，模型调用慢且可能超时 —— 直接在这里打标会拖死请求线程；
     * 它只把任务写进 Outbox，真正的打标仍由消费者限流执行，与实时链路共用同一套重试。
     *
     * 【游标为什么不会死循环？】
     * selectApprovedWithoutTags 按 content_id 升序 + tags IS NULL 扫描，
     * 一旦某帖被打过标（tags 非 NULL），它天然退出候选集 ——
     * **处理进度写在数据本身上**（与 tagContent 的 NULL 语义一致），游标只做加速。
     *
     * 【边界】limit 被 maxBackfillBatchSize 收紧，防止一次派发过量事件；
     * properties.isEnabled() 关闭时直接返回原游标，等于整体短路。
     */
    @Override
    @Transactional
    public long enqueueBackfill(long afterId, int limit) {
        if (afterId < 0) {
            throw new IllegalArgumentException("afterId 不能小于 0");
        }
        if (limit <= 0) {
            throw new IllegalArgumentException("limit 必须大于 0");
        }
        if (!properties.isEnabled()) {
            return afterId;
        }

        int effectiveLimit = Math.min(limit, properties.getMaxBackfillBatchSize());
        List<Content> contents = contentMapper.selectApprovedWithoutTags(afterId, effectiveLimit);
        if (contents == null || contents.isEmpty()) {
            return afterId;
        }

        long nextCursor = afterId;
        for (Content content : contents) {
            if (content == null || content.getContentId() == null) {
                continue;
            }
            contentEventProducer.createContentTopicTagEvent(content.getContentId());
            nextCursor = Math.max(nextCursor, content.getContentId());
        }
        return nextCursor;
    }

    private boolean isVisible(Content content) {
        return content != null
                && AuditStatus.APPROVED.getCode().equals(content.getAuditStatus())
                && Integer.valueOf(0).equals(content.getIsDeleted());
    }

    private ChatResponse callModel(String promptText) {
        CompletableFuture<ChatResponse> future = CompletableFuture.supplyAsync(
                TraceContext.wrapSupplier(
                        () -> chatModel.call(new Prompt(promptText))));
        try {
            return future.get(Math.max(1L, properties.getTimeoutSeconds()), TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("主题标签模型调用被中断", exception);
        } catch (TimeoutException exception) {
            future.cancel(true);
            throw new IllegalStateException("主题标签模型调用超时", exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause() == null ? exception : exception.getCause();
            throw new IllegalStateException("主题标签模型调用失败", cause);
        }
    }

    private String buildPrompt(Content content) {
        StringBuilder vocabulary = new StringBuilder();
        for (TopicCatalog.Topic topic : TopicCatalog.topics()) {
            if (vocabulary.length() > 0) {
                vocabulary.append("; ");
            }
            vocabulary.append(topic.id()).append("=").append(topic.label());
        }
        return "你是 QuantaCommunity 的内容主题分类器。\n"
                + "只从下列受控词表中选择最多 3 个最相关的主题 ID，按相关性降序返回。\n"
                + "没有命中时返回 []。只能输出 JSON 字符串数组，不要 Markdown 或解释。\n"
                + "词表：" + vocabulary + "\n"
                + "标题：" + safeText(content.getTitle()) + "\n"
                + "正文：" + safeText(content.getContent());
    }

    private List<String> parseModelTopics(ChatResponse response) {
        if (response == null || response.getResult() == null
                || response.getResult().getOutput() == null
                || response.getResult().getOutput().getText() == null) {
            throw new IllegalStateException("主题标签模型返回为空");
        }
        String text = stripCodeFence(response.getResult().getOutput().getText().trim());
        try {
            JsonNode root = objectMapper.readTree(text);
            if (root == null || !root.isArray()) {
                throw new IllegalStateException("主题标签模型未返回 JSON 数组");
            }

            List<String> rawTopics = new ArrayList<>();
            for (JsonNode node : root) {
                if (!node.isTextual() || node.textValue().isBlank()) {
                    throw new IllegalStateException("主题标签模型返回了非法标签");
                }
                rawTopics.add(node.textValue());
            }

            LinkedHashSet<String> normalized = new LinkedHashSet<>();
            for (String rawTopic : rawTopics) {
                List<String> one = TopicCatalog.normalizeTopics(List.of(rawTopic));
                if (one.isEmpty()) {
                    throw new IllegalStateException("主题标签不在受控词表中");
                }
                normalized.addAll(one);
            }
            // 业务契约上限固定为 3；配置只允许收紧，不能放宽模型输出边界。
            int maxAllowedTags = Math.min(3, properties.getMaxTags());
            if (normalized.size() > maxAllowedTags) {
                throw new IllegalStateException("主题标签数量超过上限");
            }
            return List.copyOf(normalized);
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("主题标签模型返回非法 JSON", exception);
        }
    }

    private String writeJson(List<String> topics) {
        try {
            return objectMapper.writeValueAsString(topics);
        } catch (Exception exception) {
            throw new IllegalStateException("主题标签序列化失败", exception);
        }
    }

    private String stripCodeFence(String text) {
        if (text.startsWith("```") && text.endsWith("```")) {
            int firstLineEnd = text.indexOf('\n');
            if (firstLineEnd >= 0) {
                return text.substring(firstLineEnd + 1, text.length() - 3).trim();
            }
        }
        return text;
    }

    private String safeText(String value) {
        return value == null ? "" : value;
    }
}
