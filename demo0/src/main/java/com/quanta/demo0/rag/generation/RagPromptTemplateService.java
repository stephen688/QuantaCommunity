package com.quanta.demo0.rag.generation;


import com.quanta.demo0.rag.model.RagCandidate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;

import java.util.List;

/**
        * RAG Prompt 模板服务(组装 Prompt)
 * ============================
         * 作用说明
 * ============================
         * 这个类负责构造 RAG 生成阶段的 Prompt，将用户问题和检索到的参考资料组装成完整的提示词。
        * 在 RAG 架构中的角色：
        *   检索结果 → RagPromptTemplateService（组装 Prompt） → 交给 RagGenerationService 调用大模型
 * Prompt 模板要求：
        *   1. 明确"仅可基于参考资料回答，不得编造"
        *   2. 输出"小红书口语化总结 + 关键要点列表"
        *   3. 资料不足时明确说"暂无足够信息"
        * ============================
        * 执行流程（一步步拆解）
        * ============================
        * 第 1 步：接收参数
 *   query: 用户搜索关键词
 *   references: 融合排序后的候选帖子列表（Top10）
        * 第 2 步：校验参考资料
 *   如果 references 为空 → 返回兜底 Prompt（告知无资料）
        * 第 3 步：格式化参考资料
 *   遍历 references，提取 title 和 contentSnippet
 *   按序号拼接成"参考资料 1、参考资料 2..."格式
 * 第 4 步：组装完整 Prompt
 *   系统提示词 + 用户问题 + 参考资料列表
 *   系统提示词包含：角色设定、回答规则、输出格式要求
 * 第 5 步：返回 Prompt 字符串
 *   交给 RagGenerationService 调用 DeepSeek 生成总结
 * ============================
         * Prompt 模板结构
 * ============================
         * 【系统提示词】
        * 你是一个校园社区助手，请基于以下参考资料回答用户问题。
        * 规则：
        * 1. 仅可基于参考资料回答，不得编造
 * 2. 如果资料不足，明确说"暂无足够信息"
        * 3. 输出风格：小红书口语化总结 + 关键要点列表
         * 【用户问题】
        * {query}
        * 【参考资料】
        * 参考资料 1：
        * 标题：{title1}
        * 内容：{snippet1}
        * 参考资料 2：
        * 标题：{title2}
        * 内容：{snippet2}
        * ============================
        * 异常处理策略
 * ============================
         * 参考资料为空时返回兜底 Prompt，不抛异常
 * 保证后续生成流程不会崩溃
 */
@Service
@Slf4j
public class RagPromptTemplateService {
    /**
     * 构造 RAG Prompt
     * ============================
     * @param query       用户搜索关键词
     * @param references  融合排序后的候选帖子列表（Top10）
     * @return String 完整的 Prompt 字符串
     *   如果 references 为空，返回兜底 Prompt
     */
    public String buildPrompt(String query, java.util.List<RagCandidate> references) {
        // 第 1 步：校验参考资料
        if (references == null || references.isEmpty()) {
            log.warn("[RAG-PROMPT] 参考资料为空，返回兜底 Prompt");
            return buildFallbackPrompt(query);
        }


        // 第 2 步：格式化参考资料
       String referencesText =formatReferences(references);

        // 第 3 步：组装完整 Prompt
        String prompt = buildSystemPrompt() + "\n\n"
                + "【用户问题】\n" + query + "\n\n"
                + "【参考资料】\n" + referencesText;

        log.info("[RAG-PROMPT] Prompt 构造完成: query='{}'，参考资料数量={}", query, references.size());
        return prompt;
    }


    /**
     * 构建系统提示词（角色设定 + 回答规则 + 输出格式要求）
     * @return
     */
    private String buildSystemPrompt() {
        return "你是一个校园社区助手，请基于以下参考资料回答用户问题。\n\n"
                + "回答规则：\n"
                + "1. 仅可基于参考资料回答，不得编造任何信息\n"
                + "2. 如果参考资料不足以回答问题，明确说'暂无足够信息'\n"
                + "3. 输出风格：小红书口语化总结 + 关键要点列表\n"
                + "4. 回答要简洁明了，适合大学生阅读";
    }


    /**
     * 格式化参考资料
     * 根据 docKind 区分帖子和回答的展示格式
     */
    private String formatReferences(List<RagCandidate> references) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < references.size(); i++) {
            RagCandidate candidate = references.get(i);

            // 根据文档类型添加不同标签
            if ("ANSWER".equals(candidate.getDocKind())) {
                sb.append("相关回答 ").append(i + 1).append("：\n");
            } else {
                sb.append("参考资料 ").append(i + 1).append("：\n");
            }

            sb.append("标题：").append(candidate.getTitle() != null ? candidate.getTitle() : "无标题").append("\n");
            sb.append("内容：").append(candidate.getContentSnippet() != null ? candidate.getContentSnippet() : "无内容").append("\n\n");
        }
        return sb.toString();
    }

/**     * 构建兜底 Prompt（参考资料为空时使用）
     * @param query 用户搜索关键词
     * @return 兜底 Prompt 字符串
     */
    private String buildFallbackPrompt(String query) {
        return "你是一个校园社区助手。\n\n"
                + "用户问题：" + query + "\n\n"
                + "抱歉，暂无足够信息回答该问题。";
    }


}
