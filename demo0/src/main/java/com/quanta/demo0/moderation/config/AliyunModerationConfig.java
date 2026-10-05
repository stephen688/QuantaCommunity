// d:/download/资料/day01/后端初始工程/demo0/src/main/java/com/quanta/demo0/config/AliyunModerationConfig.java
package com.quanta.demo0.moderation.config;

import com.aliyun.green20220302.Client;
import com.aliyun.teaopenapi.models.Config;
import com.quanta.demo0.moderation.properties.AliyunModerationProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
public class AliyunModerationConfig {

    /**
     * 阿里云内容安全（green-cip）SDK 客户端，单例 Bean。
     * 文本、图片两个审核客户端（AliyunTextModerationClient / AliyunImageModerationClient）
     * 注入的都是它——AK/SK、endpoint 只在一处装配，换云厂商只动这个类。
     *
     * 【设计：fail-fast】AK/SK 配错或 endpoint 不可用时直接抛异常，
     * **让应用在启动阶段就失败**，而不是等第一笔审核请求才报错。
     * 凭据来自环境变量（application.yml 的 ${ALIYUN_ACCESS_KEY_ID} 等），不落代码库。
     */
    @Bean
    public Client aliyunGreenClient(AliyunModerationProperties props) {
        try {
            Config config = new Config()
                    .setAccessKeyId(props.getAccessKeyId())
                    .setAccessKeySecret(props.getAccessKeySecret())
                    .setEndpoint(props.getEndpoint());

            log.info("阿里云内容安全客户端初始化成功，Endpoint: {}", props.getEndpoint());
            return new Client(config);
        } catch (Exception e) {
            log.error("阿里云内容安全客户端初始化失败", e);
            throw new RuntimeException("Aliyun Green Client Init Failed", e);
        }
    }
}