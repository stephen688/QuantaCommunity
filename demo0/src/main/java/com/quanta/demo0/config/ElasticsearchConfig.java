package com.quanta.demo0.config;

import lombok.extern.slf4j.Slf4j;
import org.apache.http.HttpHost;

import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestHighLevelClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;


/**
 * Elasticsearch 配置类
 * 作用：创建 ES 客户端、自动创建索引
 */
@Configuration
@Slf4j
public class ElasticsearchConfig {

    @Value("${spring.elasticsearch.host}")
    private String host;

    @Value("${spring.elasticsearch.port}")
    private Integer port;

    @Value("${spring.elasticsearch.scheme:http}")
    private String scheme;

    /**
     * 索引名称
     */
    private static final String INDEX_NAME = "content";

    /**
     * 创建 ES 客户端
     */
    @Bean
    public RestHighLevelClient restHighLevelClient() {
        return new RestHighLevelClient(
                RestClient.builder(
                        new HttpHost(host, port, scheme)
                )
        );
    }
}