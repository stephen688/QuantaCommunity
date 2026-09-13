package com.quanta.demo0.demo0;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.quanta.demo0.config.RecommendFeedInitializer;
import com.quanta.demo0.es.initializer.ElasticsearchIndexInitializer;
import com.quanta.demo0.rag.vector.VectorStoreInitializer;

@SpringBootTest(properties = {
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "spring.task.scheduling.enabled=false",
        "quanta.recommend.warmup-on-startup=false",
        "rag.enabled=false",
        "rag.ai-enabled=false"
})
class Demo0ApplicationTests {

    @MockitoBean
    private RecommendFeedInitializer recommendFeedInitializer;

    @MockitoBean
    private ElasticsearchIndexInitializer elasticsearchIndexInitializer;

    @MockitoBean
    private VectorStoreInitializer vectorStoreInitializer;

    @Test
    void contextLoads() {
    }

}
