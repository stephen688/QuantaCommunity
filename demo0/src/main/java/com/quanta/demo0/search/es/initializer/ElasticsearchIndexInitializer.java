package com.quanta.demo0.search.es.initializer;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.io.StringReader;

/**
 * ES 索引初始化器（继承 ApplicationRunner，用于应用启动后自动创建索引）
 * 作用：应用启动后自动创建索引
 * 包括内容索引和回答索引，因为搜索时可以搜索回答也可以搜索内容
 *
 * ============================================================
 * 【为什么在应用启动时建索引，而不是靠运维手工建？】
 * ============================================================
 * ApplicationRunner 在 Spring 容器全部就绪后执行一次：先探测索引是否存在，
 * 存在就跳过，不存在才按下面的 JSON 创建 —— **mapping 即代码**：新环境把
 * 应用拉起来索引就位，不依赖手工 SOP，重复启动天然幂等。
 *
 * ============================================================
 * 【ik_max_word 写入、ik_smart 搜索 —— 双分词器怎么分工？】
 * ============================================================
 * title/content 两个分词字段：analyzer（写入侧）用 ik_max_word 最细粒度
 * 切词，一句话尽量多切出词项，保证"能被搜到"；search_analyzer（查询侧）
 * 用 ik_smart 粗粒度切词，避免查询串被切得过碎引入噪声结果。
 * **索引侧要召回宽，查询侧要意图准**，两个 ik 分词器各管一头。
 *
 * ============================================================
 * 【单分片 0 副本 + 建索引失败不阻断启动】
 * ============================================================
 * number_of_shards=1 / number_of_replicas=0：单机部署没有第二台节点放
 * 副本，分片多了反而增加查询合并开销。创建异常被整体捕获后只 log.warn，
 * **ES 暂时不可用不让应用起不来**；索引缺失要等下次重启补建，期间若先有
 * 写入，ES 默认的自动建索引行为可能按动态映射建出没有 ik 分词器的索引，
 * 部署时要盯住这条 warn 日志。
 */
@Component
@Slf4j
public class ElasticsearchIndexInitializer implements ApplicationRunner {

    @Autowired
    private ElasticsearchClient client;

    /**
     * 索引名是裸字面量：与 ContentDocumentMapper.INDEX_NAME、
     * ElasticsearchQueryFactory.CONTENT_INDEX_NAME 三处各写了一份，改名必须同步。
     */
    private static final String INDEX_NAME = "content";

    private static final String ANSWER_INDEX_NAME = "answer";
    /**
     * 启动后创建 content 与 answer 两个索引；先 exists 后 create，已存在即跳过
     * （幂等，也不会覆盖既有 mapping —— ES 字段类型一旦定型只能增不能改）。
     *
     * 【坑】content 与 answer 的创建在同一个 try 里顺序执行：若 content 阶段
     * 抛异常会直接跳到外层 catch，这次连 answer 索引都不会去尝试；两者互相
     * 独立，排查时看日志分别确认。
     */
    @Override
    public void run(ApplicationArguments args) {
        try {
            boolean exists = client.indices().exists(request -> request.index(INDEX_NAME)).value();

            if (exists) {
                log.info("ES 索引 [{}] 已存在，跳过创建", INDEX_NAME);

            }else {
                // mapping 要点：需要分词的只有 title/content（其余全是精确值，
                // 给 filter/term 查询用）；createTime 声明多种可接受格式 —— ES
                // 内部把 date 存成 epoch 毫秒数，查询读回时可能是数字，见
                // ContentIndexServiceImpl.parseCreateTime 的 Number 分支。
                String mappingJson = """
                    {
                      "settings": {
                        "number_of_shards": 1,
                        "number_of_replicas": 0
                      },
                      "mappings": {
                        "properties": {
                          "contentId": { "type": "long" },
                          "contentType": { "type": "integer" },
                          "title": { 
                            "type": "text",
                            "analyzer": "ik_max_word",
                            "search_analyzer": "ik_smart"
                          },
                          "content": { 
                            "type": "text",
                            "analyzer": "ik_max_word",
                            "search_analyzer": "ik_smart"
                          },
                          "publishUserId": { "type": "long" },
                          "auditStatus": { "type": "integer" },
                          "liked": { "type": "integer" },
                          "collectCount": { "type": "integer" },
                          "commentCount": { "type": "integer" },
                          "createTime": { 
                            "type": "date",
                          "format": "yyyy-MM-dd HH:mm:ss||yyyy-MM-dd'T'HH:mm:ss||yyyy-MM-dd'T'HH:mm:ss.SSS'Z'||epoch_millis"
                          },
                          "isDeleted": { "type": "integer" }
                        }
                      }
                    }
                    """;

                client.indices().create(request -> request
                        .index(INDEX_NAME)
                        .withJson(new StringReader(mappingJson)));

                log.info("ES 索引 [{}] 创建成功", INDEX_NAME);
            }
            // 创建回答索引（无论 content 索引是否存在都要执行）
            createAnswerIndexIfNotExists();// 创建回答索引（如果不存在 则创建 ）



        } catch (Exception e) {
            log.warn("ES 索引创建失败", e);
        }
    }
    /**
     * 创建回答索引（如果不存在 则创建 ）
     *
     * 与 content 索引同构：questionTitle/answerContent 同样是"写入 ik_max_word、
     * 搜索 ik_smart"的双分词器；isAccepted（是否被采纳 0/1）、likeCount 等
     * 计数字段作为精确值随文档冗余存储。
     */
    private void createAnswerIndexIfNotExists() {
        try {
            boolean exists = client.indices().exists(request -> request.index(ANSWER_INDEX_NAME)).value();

            if (exists) {
                log.info("ES 回答索引 [{}] 已存在，跳过创建", ANSWER_INDEX_NAME);
                return;
            }

            // 字段名（camelCase）必须与 ContentDocumentMapper.toSource 写入的
            // key 完全一致，两边对不上字段会静默丢进动态映射。
            String mappingJson = """
                {
                  "settings": {
                    "number_of_shards": 1,
                    "number_of_replicas": 0
                  },
                  "mappings": {
                    "properties": {
                      "answerId": { "type": "long" },
                      "questionId": { "type": "long" },
                      "questionTitle": { 
                        "type": "text",
                        "analyzer": "ik_max_word",
                        "search_analyzer": "ik_smart"
                      },
                      "answerContent": { 
                        "type": "text",
                        "analyzer": "ik_max_word",
                        "search_analyzer": "ik_smart"
                      },
                      "userId": { "type": "long" },
                      "auditStatus": { "type": "integer" },
                      "likeCount": { "type": "integer" },
                      "commentCount": { "type": "integer" },
                      "isAccepted": { "type": "integer" },
                      "createTime": { 
                        "type": "date",
                        "format": "yyyy-MM-dd HH:mm:ss||yyyy-MM-dd'T'HH:mm:ss||yyyy-MM-dd'T'HH:mm:ss.SSS'Z'||epoch_millis"
                      },
                      "isDeleted": { "type": "integer" }
                    }
                  }
                }
                """;

            client.indices().create(request -> request
                    .index(ANSWER_INDEX_NAME)
                    .withJson(new StringReader(mappingJson)));

            log.info("ES 回答索引 [{}] 创建成功", ANSWER_INDEX_NAME);

        } catch (Exception e) {
            log.warn("ES 回答索引创建失败", e);
        }
    }
}
