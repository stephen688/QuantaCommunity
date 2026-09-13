package com.quanta.demo0.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "quanta.alioss")//告诉SpringBoot将本类中的属性和配置文件中相关的属性进行绑定
@Data
/**
 * 阿里云文件上传配置类(封装配置文件内容)
 */
public class AliOssProperties {

    private String endpoint;
    private String accessKeyId;
    private String accessKeySecret;
    private String bucketName;

}
