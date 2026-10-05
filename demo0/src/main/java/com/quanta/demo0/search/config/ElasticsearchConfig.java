package com.quanta.demo0.search.config;

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
 *
 * ============================================================
 * 【为什么自建三层 Bean，而不是用 Boot 的 ES 自动配置？】
 * ============================================================
 * pom 只引了 co.elastic.clients:elasticsearch-java（类型化客户端），连接参数
 * 读的是本项目自定义的 spring.elasticsearch.host/port/scheme（@Value 直读，
 * 见 application.yml），不是 Boot 标准的 spring.elasticsearch.uris ——
 * **依赖更薄、装配过程全部可见**。三层各司其职：
 * RestClient 管 HTTP 连接 → Transport 管 JSON 序列化 → ElasticsearchClient
 * 是 initializer/mapper 实际注入使用的类型安全门面。
 *
 * ============================================================
 * 【关闭顺序：谁负责关连接？】
 * ============================================================
 * RestClient 的 Bean 声明 destroyMethod="" 刻意不让 Spring 自动 close，
 * **由 Transport 的 close 一并关闭底层连接**（RestClientTransport.close 会
 * 关闭传入的 RestClient），避免两层重复关闭。
 */
@Configuration
@Slf4j
public class ElasticsearchConfig {

    @Value("${spring.elasticsearch.host}")
    private String host;

    @Value("${spring.elasticsearch.port}")
    private Integer port;

    // 【fail-fast 取舍】scheme 给了默认值 http，host/port 不给 —— 缺配置
    // 启动即失败，比运行期连不上更容易定位。
    @Value("${spring.elasticsearch.scheme:http}")
    private String scheme;

    /**
     * 创建 ES 低级 REST 客户端。
     *
     * destroyMethod=""：关闭职责让给下面的 Transport（见类注释），
     * 只声明 host/port/scheme，没有账号密码 —— 接入带认证的集群时在这里扩展。
     */
    @Bean(destroyMethod = "")
    public RestClient elasticsearchRestClient() {
        return RestClient.builder(new HttpHost(host, port, scheme)).build();
    }

    /**
     * Transport 负责 JSON 映射，并在关闭时一并关闭底层 RestClient。
     *
     * JacksonJsonpMapper 复用 Spring 容器里 Boot 配好的 ObjectMapper：
     * LocalDateTime 等 java.time 类型的序列化行为与全局 REST 接口保持一致。
     */
    @Bean(destroyMethod = "close")
    public ElasticsearchTransport elasticsearchTransport(RestClient elasticsearchRestClient,
                                                          ObjectMapper objectMapper) {
        return new RestClientTransport(
                elasticsearchRestClient,
                new JacksonJsonpMapper(objectMapper)
        );
    }

    /**
     * 类型化客户端门面：initializer 与两个 DocumentMapper 注入的都是它。
     */
    @Bean
    public ElasticsearchClient elasticsearchClient(ElasticsearchTransport elasticsearchTransport) {
        return new ElasticsearchClient(elasticsearchTransport);
    }
}
