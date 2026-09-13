package com.quanta.demo0.rag.retrieval;
import com.github.pagehelper.Page;
import com.quanta.demo0.entity.Content;
import com.quanta.demo0.es.document.AnswerDocument;
import com.quanta.demo0.es.service.ElasticSearchService;
import com.quanta.demo0.properties.RagProperties;
import com.quanta.demo0.rag.model.RagCandidate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.ArrayList;
import java.util.List;


/**
 * ES 召回服务
 * ============================
 * 作用说明
 * ============================
 * 这个类负责用 Elasticsearch 执行关键词检索，召回 Top20 相关帖子。
 * 在双路检索中的角色：
 * 双路检索 = ES 召回 + 向量召回
 * ES 召回负责"关键词精准匹配"（用户搜"考研"，ES 找标题/正文含"考研"的帖子）
 * 向量召回负责"语义模糊匹配"（用户搜"考研准备"，向量找语义相关但没出现"考研"二字的帖子）
 * ============================
 * 执行流程（一步步拆解）
 * ============================
 * 第 1 步：参数校验
 * query 不能为空，为空直接返回空列表
 * 第 2 步：构建 ES 查询请求
 * 使用 BoolQuery 组合多个条件：
 * must: 关键词匹配（title 或 content 字段）
 * filter: contentType 过滤（如果传了的话）
 * filter: isDeleted=0（只查未删除的）
 * filter: auditStatus=1（只查审核通过的）
 * 第 3 步：执行 ES 查询
 * 调用 RestHighLevelClient.search()
 * 按 _score（相关度得分）降序排序
 * 限制返回数量为 topK（默认 20）
 * 第 4 步：解析 ES 结果
 * 遍历 SearchHit，提取：
 * contentId: 帖子 ID
 * title: 帖子标题
 * contentSnippet: 帖子正文（截取前 200 字作为摘要）
 * esScore: ES 相关度得分（_score）
 * 填充到 RagCandidate 对象中
 * RagCandidate:用来暂存 ES 召回的帖子信息，后续会和向量召回结果合并
 * 第 5 步：返回结果
 * 成功 → 返回 RagCandidate 列表
 * 失败 → 返回空列表（不抛异常，避免影响主流程）
 * ============================
 * 异常处理策略
 * ============================
 * ES 查询失败时返回空列表，不抛异常
 * 原因：
 * ES 召回只是双路检索的一路，失败不影响向量召回
 * 最坏情况：只有向量召回的结果，搜索体验稍差但功能可用
 */

@Service
@Slf4j
public class EsRecallService {


    @Autowired
    private RagProperties ragProperties;
    @Autowired
    private ElasticSearchService elasticSearchService;

  //  private static final String INDEX_NAME = "content"; // ES 索引名称

    /**
     * ES 召回方法
     * ============================
     *
     * @param query       搜索关键词（用户输入的原始查询）
     * @param contentType 内容类型过滤：1-生活区 2-专业区 null-全部
     * @param topK        召回数量（默认用 ragProperties.esTopK）
     * @return List<RagCandidate> ES 召回的候选帖子列表
     * 每个 RagCandidate 包含：contentId、title、contentSnippet、esScore、source="ES"
     * 如果 ES 查询失败或无结果，返回空列表
     */
    public List<RagCandidate> recall(String query, Integer contentType, int topK) {
        //1. 参数校验
        if (query == null || query.trim().isEmpty()) {
            log.warn("ES 召回失败：查询关键词为空");
            return List.of();
        }
        if (topK <= 0) {
            topK = ragProperties.getEsTopK();// 使用默认值
        }
        try {
            //2. 构建 ES 查询请求 - 帖子召回
            Page<Content> page = elasticSearchService.searchContent(query, contentType, 1, topK);

            // 3. 转换为 RagCandidate（帖子）
            List<RagCandidate> candidates = new ArrayList<>();
            if (page != null && !page.isEmpty()) {
                for (Content content : page) {
                    String contentSnippet = content.getContent() != null && content.getContent().length() > 200
                            ? content.getContent().substring(0, 200)
                            : content.getContent();

                    RagCandidate candidate = RagCandidate.builder()
                            .docKind("POST")
                            .contentId(content.getContentId())
                            .answerId(null)
                            .title(content.getTitle())
                            .contentSnippet(contentSnippet)
                            .esScore(content.getEsSearchScore())
                            .source("ES")
                            .fusionScore(null)
                            .vectorScore(null)
                            .build();
                    candidates.add(candidate);
                }
            }

            // 4. ES 回答召回
          //  List<AnswerDocument> answerDocs = elasticSearchService.searchAnswers(query, topK);
            // 4. ES 回答召回（仅专业区或全部时召回，生活区不召回回答）
            if (contentType == null || contentType == 2) {
                List<AnswerDocument> answerDocs = elasticSearchService.searchAnswers(query, topK);
                if (answerDocs != null && !answerDocs.isEmpty()) {
                    for (AnswerDocument doc : answerDocs) {
                        String contentSnippet = doc.getAnswerContent() != null && doc.getAnswerContent().length() > 200
                                ? doc.getAnswerContent().substring(0, 200)
                                : doc.getAnswerContent();

                        RagCandidate candidate = RagCandidate.builder()
                                .docKind("ANSWER")
                                .contentId(doc.getQuestionId())
                                .answerId(doc.getAnswerId())
                                .title(doc.getQuestionTitle())
                                .contentSnippet(contentSnippet)
                                .esScore(doc.getEsSearchScore() != null ? doc.getEsSearchScore() : 0.0)
                                .source("ES")
                                .fusionScore(null)
                                .vectorScore(null)
                                .build();
                        candidates.add(candidate);
                    }
                }
            }

            //5. 返回结果
            log.info("ES 召回成功：查询关键词='{}'，contentType={}，topK={}，命中数={}", query, contentType, topK, candidates.size());
            return candidates;
        } catch (Exception e) {
            //3. ES 查询失败，记录日志并返回空列表
            log.error("ES 召回失败：查询执行异常", e);
            return List.of();
        }
    }
}
