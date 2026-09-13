package com.quanta.demo0.es.initializer;

import lombok.extern.slf4j.Slf4j;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.client.indices.CreateIndexRequest;
import org.elasticsearch.client.indices.GetIndexRequest;
import org.elasticsearch.common.xcontent.XContentType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * ES 索引初始化器（继承 ApplicationRunner，用于应用启动后自动创建索引）
 * 作用：应用启动后自动创建索引
 * 包括内容索引和回答索引，因为搜索时可以搜索回答也可以搜索内容
 */
@Component
@Slf4j
public class ElasticsearchIndexInitializer implements ApplicationRunner {

    @Autowired
    private RestHighLevelClient client;

    private static final String INDEX_NAME = "content";

    private static final String ANSWER_INDEX_NAME = "answer";
    @Override
    public void run(ApplicationArguments args) {
        try {
            GetIndexRequest getIndexRequest = new GetIndexRequest(INDEX_NAME);
            boolean exists = client.indices().exists(getIndexRequest, RequestOptions.DEFAULT);

            if (exists) {
                log.info("ES 索引 [{}] 已存在，跳过创建", INDEX_NAME);

            }else {
                // 创建内容索引
                CreateIndexRequest createIndexRequest = new CreateIndexRequest(INDEX_NAME);

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

                createIndexRequest.source(mappingJson, XContentType.JSON);// 设置索引映射
                client.indices().create(createIndexRequest, RequestOptions.DEFAULT); // 创建索引

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
     */
    private void createAnswerIndexIfNotExists() {
        try {
            GetIndexRequest getIndexRequest = new GetIndexRequest(ANSWER_INDEX_NAME);
            boolean exists = client.indices().exists(getIndexRequest, RequestOptions.DEFAULT);

            if (exists) {
                log.info("ES 回答索引 [{}] 已存在，跳过创建", ANSWER_INDEX_NAME);
                return;
            }

            CreateIndexRequest createIndexRequest = new CreateIndexRequest(ANSWER_INDEX_NAME);

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

            createIndexRequest.source(mappingJson, XContentType.JSON);
            client.indices().create(createIndexRequest, RequestOptions.DEFAULT);

            log.info("ES 回答索引 [{}] 创建成功", ANSWER_INDEX_NAME);

        } catch (Exception e) {
            log.warn("ES 回答索引创建失败", e);
        }
    }
}