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
 */
@Component
public class ElasticsearchQueryFactory {

    private static final String CONTENT_INDEX_NAME = "content";
    private static final String ANSWER_INDEX_NAME = "answer";

    /**
     * 构造内容分页查询，保留原有过滤、分页、排序和高亮语义。
     */
    public SearchRequest contentSearch(String keyword, Integer contentType, int from, int size) {
        BoolQuery.Builder boolQuery = new BoolQuery.Builder()
                .must(multiMatch(keyword, "title", "content"))
                .filter(termQuery("isDeleted", 0))
                .filter(termQuery("auditStatus", 1));
        if (contentType != null) {
            boolQuery.filter(termQuery("contentType", contentType));
        }
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

    private Query multiMatch(String keyword, String... fields) {
        return Query.of(query -> query.multiMatch(multiMatch -> multiMatch
                .query(keyword)
                .fields(List.of(fields))
                .type(TextQueryType.BestFields)
                .fuzziness("AUTO")));
    }

    private Query termQuery(String field, int value) {
        return Query.of(query -> query.term(term -> term.field(field).value(value)));
    }
}
