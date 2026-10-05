package com.quanta.demo0.moderation.utils;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 敏感词检查器：内容安全链路的第一道闸（发布入口同步调用，不经过 MQ）。
 *
 * 使用方（已核实）：content/answer/comment 的 CommandService 在落库前用
 * {@link #findFirstHit} 拦截命中敏感词的发布；用户昵称场景用
 * {@link #replaceSensitiveWords} 打码（见 UserProfileServiceImpl / UserAccountServiceImpl）。
 * 拦不住漏网之鱼——后面还有阿里云机审（AliyunText/ImageModerationClient）兜底。
 *
 * ============================================================
 * 【为什么用 HashSet + contains 遍历，而不是 DFA/AC 自动机？】
 * ============================================================
 * 词库是 classpath 下百余行的 sensitive-words.txt（一行一词、# 注释），
 * 规模小，O(词数) 次 contains 的开销可忽略，20 行代码就够用；
 * DFA/AC 自动机适合十万级词库或高 QPS 场景，在这里属于提前优化。
 * 【坑】匹配是"子串包含"而非分词——过短的词会误伤正常用语（词库头部注释也提醒了）；
 * 大小写不敏感靠统一 toLowerCase 实现，替换时再用 (?i) 正则回写原文。
 */
@Component
@Slf4j
public class SensitiveWordChecker {

    /** 词库常驻内存（HashSet），进程生命周期内只加载一次 */
    private final Set<String> words = new HashSet<>();

    // 初始化敏感词库
    /**
     * 启动时把 classpath 下的 sensitive-words.txt（UTF-8）整表载入内存：
     * 逐行 trim + 转小写，空行与 # 注释行跳过。
     * 【设计：fail-fast】词库文件缺失直接抛异常让应用起不来，
     * 不允许"没词库也照常上线"的静默裸奔。
     */
    @PostConstruct
    public void init() {
        ClassPathResource resource = new ClassPathResource("sensitive-words.txt");
        if (!resource.exists()) {
           log.error("敏感词库文件不存在"); // 没有词库时不拦截，避免启动失败
            throw new RuntimeException("敏感词库文件不存在");
        }
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String w = line.trim().toLowerCase(Locale.ROOT);
                if (!w.isEmpty() && !w.startsWith("#")) {
                    words.add(w);
                }
            }
        } catch (Exception ignored) {
            // 可改为日志记录
            log.error("加载敏感词库失败", ignored);
        }
    }

    // 返回文本中第一个出现的敏感词，找不到则返回 null
    /**
     * 【坑】"第一个"指词库遍历顺序中先命中的词——words 是 HashSet，无序，
     * 所以返回哪个命中词不确定，更不是文本里位置最早的敏感词。
     * 调用方只用它做存在性判断/拼提示文案，不受影响。
     */
    public String findFirstHit(String text) {
        if (text == null || text.isBlank() || words.isEmpty()) {
            return null;
        }
        // 统一转小写后再做子串匹配，实现大小写不敏感
        String normalized = text.toLowerCase(Locale.ROOT);
        for (String w : words) {
            if (normalized.contains(w)) {
                return w;
            }
        }
        return null;
    }

    // 将文本中的敏感词替换为 *
    /**
     * 命中的敏感词替换成等长的 * 串（"赌博" → "**"），返回脱敏后的文本。
     * 【实现细节】contains 判断在 toLowerCase 后的副本上做，替换时用
     * "(?i)" + Pattern.quote(w) 正则回写原文——既忽略大小写，又保留原文其余部分
     * 的大小写与格式；Pattern.quote 把词当字面量，防止词库里的正则特殊字符破坏表达式。
     */
    public String replaceSensitiveWords(String text) {
        if (text == null || text.isBlank() || words.isEmpty()) {
            return text;
        }
        String result = text;// 保持原文本的大小写和格式，只在替换时忽略大小写
        String normalized = text.toLowerCase(Locale.ROOT);// 用于匹配敏感词，忽略大小写
        for (String w : words) {
            if (normalized.contains(w)) {
                // 用 * 替换敏感词，保持长度一致
                String stars = "*".repeat(w.length());
                result = result.replaceAll("(?i)" + Pattern.quote(w), stars);// (?i) 表示忽略大小写，Pattern.quote(w) 用于转义敏感词中的特殊字符
            }
        }
        return result;
    }


    // 返回所有敏感词
    // 【封装】unmodifiableSet 只读视图——外部能看词库，但改不了它
    public Set<String> getAllWords() {
        return Collections.unmodifiableSet(words);
    }
}
