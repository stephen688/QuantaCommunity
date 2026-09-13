// d:/download/资料/day01/后端初始工程/demo0/src/main/java/com/quanta/demo0/config/AliyunModerationConfig.java
package com.quanta.demo0.config;

import com.aliyun.green20220302.Client;
import com.aliyun.teaopenapi.models.Config;
import com.quanta.demo0.properties.AliyunModerationProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
public class AliyunModerationConfig {

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