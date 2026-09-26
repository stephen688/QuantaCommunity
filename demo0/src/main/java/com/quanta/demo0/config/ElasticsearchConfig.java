package com.quanta.demo0.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
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
     * 创建 ES 低级 REST 客户端。
     */
    @Bean(destroyMethod = "")
    public RestClient elasticsearchRestClient() {
        return RestClient.builder(new HttpHost(host, port, scheme)).build();
    }

    /**
     * Transport 负责 JSON 映射，并在关闭时一并关闭底层 RestClient。
     */
    @Bean(destroyMethod = "close")
    public ElasticsearchTransport elasticsearchTransport(RestClient elasticsearchRestClient,
                                                          ObjectMapper objectMapper) {
        return new RestClientTransport(
                elasticsearchRestClient,
                new JacksonJsonpMapper(objectMapper)
        );
    }

    @Bean
    public ElasticsearchClient elasticsearchClient(ElasticsearchTransport elasticsearchTransport) {
        return new ElasticsearchClient(elasticsearchTransport);
    }
}
