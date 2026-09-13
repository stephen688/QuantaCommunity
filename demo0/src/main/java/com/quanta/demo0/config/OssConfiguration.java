package com.quanta.demo0.config;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.common.comm.Protocol;
import com.quanta.demo0.properties.AliOssProperties;
import com.quanta.demo0.utils.AliOssUtil;
import com.aliyun.oss.ClientBuilderConfiguration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 阿里云 OSS：单例客户端复用连接，避免每次上传新建 TCP/TLS。
 */
@Configuration
@Slf4j
public class OssConfiguration {

    @Bean(destroyMethod = "shutdown")
    @ConditionalOnMissingBean
    public OSS ossClient(AliOssProperties props) {
        ClientBuilderConfiguration config = new ClientBuilderConfiguration();
        config.setProtocol(Protocol.HTTPS);
        config.setConnectionTimeout(10_000);
        config.setSocketTimeout(60_000);
        config.setMaxConnections(200);
        config.setMaxErrorRetry(2);
        config.setConnectionRequestTimeout(5_000);
        config.setIdleConnectionTime(60_000);

        OSS client = new OSSClientBuilder().build(
                props.getEndpoint(),
                props.getAccessKeyId(),
                props.getAccessKeySecret(),
                config);
        log.info("OSS 客户端初始化完成 endpoint={}, bucket={}", props.getEndpoint(), props.getBucketName());
        return client;
    }

    @Bean
    @ConditionalOnMissingBean
    public AliOssUtil aliOssUtil(OSS ossClient, AliOssProperties props) {
        return new AliOssUtil(ossClient, props.getEndpoint(), props.getBucketName());
    }
}
