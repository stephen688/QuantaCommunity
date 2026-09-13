package com.quanta.demo0.utils;

import com.aliyun.oss.ClientException;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSException;
import com.aliyun.oss.model.ObjectMetadata;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayInputStream;

@Slf4j
@RequiredArgsConstructor
public class AliOssUtil {

    private static final int MAX_ATTEMPTS = 3;

    private final OSS ossClient;
    private final String endpoint;
    private final String bucketName;

    /**
     * 上传字节到 OSS，失败时抛出异常（不再误返回 URL）。
     */
    public String upload(byte[] bytes, String objectName) {
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentLength(bytes.length);

        RuntimeException lastError = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                ossClient.putObject(bucketName, objectName, new ByteArrayInputStream(bytes), metadata);
                String url = buildPublicUrl(objectName);
                log.info("文件上传成功: {}", url);
                return url;
            } catch (OSSException oe) {
                log.error("OSS 拒绝上传 object={}, code={}, message={}, requestId={}",
                        objectName, oe.getErrorCode(), oe.getErrorMessage(), oe.getRequestId());
                throw new RuntimeException("OSS 上传失败: " + oe.getErrorMessage(), oe);
            } catch (ClientException ce) {
                lastError = new RuntimeException("OSS 连接失败: " + ce.getMessage(), ce);
                log.warn("OSS 上传第 {}/{} 次失败 object={}, error={}",
                        attempt, MAX_ATTEMPTS, objectName, ce.getMessage());
                if (attempt < MAX_ATTEMPTS) {
                    sleepQuietly(attempt * 500L);
                }
            }
        }
        throw lastError != null ? lastError : new RuntimeException("OSS 上传失败");
    }

    private String buildPublicUrl(String objectName) {
        return "https://" + bucketName + "." + endpoint + "/" + objectName;
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
