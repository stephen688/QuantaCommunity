package com.quanta.demo0.utils;

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

@Component
@Slf4j
public class SensitiveWordChecker {

    private final Set<String> words = new HashSet<>();


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
    public String findFirstHit(String text) {
        if (text == null || text.isBlank() || words.isEmpty()) {
            return null;
        }
        String normalized = text.toLowerCase(Locale.ROOT);
        for (String w : words) {
            if (normalized.contains(w)) {
                return w;
            }
        }
        return null;
    }

    // 将文本中的敏感词替换为 *
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
    public Set<String> getAllWords() {
        return Collections.unmodifiableSet(words);
    }
}
