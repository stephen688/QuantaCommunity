package com.quanta.demo0.search.es.query;

import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch._types.query_dsl.TextQueryType;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 搜索域 Elasticsearch 查询工厂。
 *
 * <p>集中维护索引名、过滤条件、排序和高亮契约；文档 Mapper 只负责把请求提交给 ES，
 * 不在基础设施适配器中重复拼装业务查询。</p>
 *
 * ============================================================
 * 【可见性过滤为什么放 filter 子句而不是 must？】
 * ============================================================
 * isDeleted=0、auditStatus=1 是"要不要"而不是"有多相关"：放进 filter 上下文
 * 既不影响 BM25 相关性得分，又能被 ES 缓存位图复用；混进 must 会把状态字段
 * 当成文本参与打分，污染排序。这里的口径与写入侧 ContentIndexServiceImpl.isVisible
 * （is_deleted=0 AND audit_status=1，与 content 包查询口径一致）是同一把尺子的
 * 两端：**写入时已删掉不可见文档，查询时再过滤一次，双保险兜住对账间隙的脏数据**。
 *
 * ============================================================
 * 【multiMatch + fuzziness=AUTO 的检索体验取舍】
 * ============================================================
 * title 与 content 一起 multiMatch（BestFields：按命中最好的字段计分，
 * 标题命中天然比正文命中"值钱"）；fuzziness=AUTO 按词长允许 1~2 个字符的
 * 编辑距离 —— 容忍错别字，代价是可能召回少量形近词。
 */
@Component
public class ElasticsearchQueryFactory {

    private static final String CONTENT_INDEX_NAME = "content";
    private static final String ANSWER_INDEX_NAME = "answer";

    /**
     * 构造内容分页查询，保留原有过滤、分页、排序和高亮语义。
     *
     * 【排序细节】_score 降序为主、createTime 降序为次 —— 相关性打平的
     * 结果新内容靠前，翻页顺序才稳定。from/size 深分页受 ES
     * max_result_window=10000 限制（上层 ContentIndexServiceImpl.searchContent
     * 已按此折算最大页码）。
     */
    public SearchRequest contentSearch(String keyword, Integer contentType, int from, int size) {
        BoolQuery.Builder boolQuery = new BoolQuery.Builder()
                .must(multiMatch(keyword, "title", "content"))
                .filter(termQuery("isDeleted", 0))
                .filter(termQuery("auditStatus", 1));
        if (contentType != null) {
            boolQuery.filter(termQuery("contentType", contentType));
        }
        // 高亮契约：命中片段包 <em></em> 返回，前端负责把标签渲染成样式。
        return SearchRequest.of(builder -> builder
                .index(CONTENT_INDEX_NAME)
                .query(boolQuery.build()._toQuery())
                .from(from)
                .size(size)
                .sort(sort -> sort.field(field -> field.field("_score").order(SortOrder.Desc)))
                .sort(sort -> sort.field(field -> field.field("createTime").order(SortOrder.Desc)))
                .highlight(highlight -> highlight
                        .preTags("<em>")
                        .postTags("</em>")
                        .fields("title", field -> field)
                        .fields("content", field -> field)));
    }

    /**
     * 构造回答召回查询，保留原有字段、过滤条件和相关性排序。
     *
     * 【召回不是分页】size=topK、只按 _score 排序、无 from —— 调用方
     * （RAG/回答搜索）要的是"最相关的 N 条"，不是第 X 页。
     */
    public SearchRequest answerSearch(String keyword, int topK) {
        BoolQuery boolQuery = new BoolQuery.Builder()
                .must(multiMatch(keyword, "questionTitle", "answerContent"))
                .filter(termQuery("isDeleted", 0))
                .filter(termQuery("auditStatus", 1))
                .build();
        return SearchRequest.of(builder -> builder
                .index(ANSWER_INDEX_NAME)
                .query(boolQuery._toQuery())
                .size(topK)
                .sort(sort -> sort.field(field -> field.field("_score").order(SortOrder.Desc))));
    }

    /**
     * 多字段关键词匹配：BestFields 按最佳命中字段计分；fuzziness=AUTO
     * 允许少量编辑距离，容忍拼写误差（详见类注释）。
     */
    private Query multiMatch(String keyword, String... fields) {
        return Query.of(query -> query.multiMatch(multiMatch -> multiMatch
                .query(keyword)
                .fields(List.of(fields))
                .type(TextQueryType.BestFields)
                .fuzziness("AUTO")));
    }

    /**
     * 精确值 term 查询：字段是 integer 不分词，只用于 filter 上下文。
     */
    private Query termQuery(String field, int value) {
        return Query.of(query -> query.term(term -> term.field(field).value(value)));
    }
}
