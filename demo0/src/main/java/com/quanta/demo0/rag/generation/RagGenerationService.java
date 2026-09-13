package com.quanta.demo0.rag.generation;


import com.quanta.demo0.properties.RagProperties;
import com.quanta.demo0.rag.model.RagCandidate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
/**
 * RAG AI 生成服务
 * ============================
 * 作用说明
 * ============================
 * 这个类负责调用 DeepSeek 大模型生成 AI 总结。
 * 在 RAG 架构中的角色：
 *   Prompt → RagGenerationService（调用大模型） → 返回 AI 总结文本
 * 核心职责：
 *   1. 注入 ChatModel（DeepSeek）
 *   2. 传入 Prompt 调用模型
 *   3. 统一超时与异常捕获，失败返回空 Optional
 * ============================
 * 执行流程（一步步拆解）
 * ============================
 * 第 1 步：接收参数
 *   query: 用户搜索关键词
 *   references: 融合排序后的候选帖子列表（Top10）
 * 第 2 步：组装 Prompt
 *   调用 ragPromptTemplateService.buildPrompt(query, references)
 *   得到完整的 Prompt 字符串
 * 第 3 步：调用 DeepSeek 大模型
 *   构造 Prompt 对象，传入 ChatModel.call()
 *   设置超时时间（从 ragProperties 读取）
 * 第 4 步：解析响应
 *   提取 Generation 中的文本内容
 *   如果响应为空或异常，返回 Optional.empty()
 * 第 5 步：返回结果
 *   成功 → Optional.of(summaryText)
 *   失败 → Optional.empty()（供上层判断 aiAvailable=false）
 * ============================
 * 异常处理策略
 * ============================
 * 所有异常统一 catch，记录日志后返回 Optional.empty()
 * AI 调用失败不影响主搜索流程，仅置 aiAvailable=false
 * 常见失败场景：
 *   - API Key 无效
 *   - 网络超时
 *   - 模型返回异常
 *   - Token 超限
 */

@Service
@Slf4j
public class RagGenerationService {


@Autowired
private ChatModel deepSeekChatModel;
@Autowired
private RagPromptTemplateService ragPromptTemplateService;
@Autowired
private RagProperties ragProperties;
    /**
     * 生成 AI 总结
     * ============================
     * @param query       用户搜索关键词
     * @param references  融合排序后的候选帖子列表（Top10）
     * @return Optional<String> AI 总结文本
     *   成功 → Optional.of(summaryText)
     *   失败 → Optional.empty()（供上层判断 aiAvailable=false）
     */
    public Optional<String> generateSummary(String query, List<RagCandidate> references) {
        //1.校验开关
        if (!ragProperties.isAiEnabled()) {
            log.info("[RAG] AI 生成开关未开启，跳过生成");
            return Optional.empty();
        }
        //2.组装 Prompt
        String promptText = ragPromptTemplateService.buildPrompt(query, references);
        //3.调用 DeepSeek 大模型
        try {
            Prompt prompt = new Prompt(promptText);
            ChatResponse chatResponse = deepSeekChatModel.call(prompt);

            //4.解析响应
            String summary = chatResponse.getResult().getOutput().getText();
            if (summary == null || summary.trim().isEmpty()) {
                log.warn("[RAG-GENERATION] DeepSeek 返回空响应");
                return Optional.empty();
            }
            log.info("[RAG-GENERATION] DeepSeek 生成成功");
            return Optional.of(summary);
        } catch (Exception e) {
            log.error("[RAG-GENERATION] DeepSeek 生成失败: error={}", e.getMessage(), e);
            return Optional.empty();
        }
    }

}
