package com.quanta.demo0.search.constant;

/**
 * Elasticsearch 索引常量
 *
 * ============================================================
 * 【现状警示：本类当前在全项目没有任何引用，勿当真源】
 * ============================================================
 * 索引名的真实来源是各处裸字面量 "content"/"answer"：ElasticsearchIndexInitializer
 * （建索引）、ContentDocumentMapper/AnswerDocumentMapper（读写）、
 * ElasticsearchQueryFactory（查询）各写了一份。且 CONTENT_INDEX 的值
 * "content_index_v1" 与实际创建的索引名 "content" **并不一致** —— 引用前
 * 先核对真实索引名；要收口的话应把字面量统一替换为本常量并改对值，
 * 三处必须同步，否则写入与查询会落到不同索引。
 */
public class EsIndexConstant {

    /**
     * 内容索引名称
     *
     * 注意：值与 initializer 实际创建的 "content" 不一致，且当前无调用方。
     */
    public static final String CONTENT_INDEX = "content_index_v1";

    /**
     * 回答索引名称
     */
    public static final String ANSWER_INDEX = "answer";
}