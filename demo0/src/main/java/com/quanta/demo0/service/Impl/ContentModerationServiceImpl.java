// d:/download/资料/day01/后端初始工程/demo0/src/main/java/com/quanta/demo0/service/Impl/ContentModerationServiceImpl.java
package com.quanta.demo0.service.Impl;


/**
 * 内容审核服务实现类
 * - 开关控制 ：总开关、目标类型开关、兜底策略
 * - 指纹防重 ：计算内容 MD5 指纹，避免重复审核浪费费用
 * - 流程编排 ：协调文本审核和图片审核客户端
 * - 结果合并 ：按优先级（REJECT > MANUAL > ERROR > PASS）合并多模态审核结果
 * - 记录持久化 ：将审核结果保存到数据库，方便追溯
 * - 失败处理 ：审核服务异常时记录错误信息
 */

import com.alibaba.fastjson.JSON;
import com.quanta.demo0.annotation.ModerationDecision;
import com.quanta.demo0.annotation.ModerationTargetType;
import com.quanta.demo0.entity.ModerationRecord;
import com.quanta.demo0.mapper.ModerationRecordMapper;

import com.quanta.demo0.modertion.client.AliyunImageModerationClient;
import com.quanta.demo0.modertion.client.AliyunTextModerationClient;
import com.quanta.demo0.modertion.result.ModerationResult;
import com.quanta.demo0.mq.message.ModerationTaskMessage;
import com.quanta.demo0.properties.AliyunModerationProperties;
import com.quanta.demo0.service.ContentModerationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Slf4j
@Service
public class ContentModerationServiceImpl implements ContentModerationService {

    @Autowired
    private AliyunModerationProperties moderationProperties;

    @Autowired
    private AliyunTextModerationClient textClient;

    @Autowired
    private AliyunImageModerationClient imageClient;

    @Autowired
    private ModerationRecordMapper moderationRecordMapper;

    @Override
    public ModerationResult moderate(ModerationTaskMessage task) {
        // 1. 检查总开关
        if (!moderationProperties.isEnabled()) {
            log.info("审核总开关关闭，跳过审核 targetId={}", task.getTargetId());
            return ModerationResult.builder().decision(ModerationDecision.PASS).build();
        }

        // 2. 检查目标类型开关
        AliyunModerationProperties.TargetConfig targetConfig = getTargetConfig(task.getTargetType());// 获取目标类型的审核配置

        if (targetConfig == null || !targetConfig.isEnabled()) {
            log.info("目标类型审核关闭，执行兜底策略 targetType={}, policy={}",
                    task.getTargetType(), targetConfig != null ? targetConfig.getDisabledPolicy() : "PENDING");

            String policy = targetConfig != null ? targetConfig.getDisabledPolicy() : "PENDING";
            if ("APPROVED".equalsIgnoreCase(policy)) {
                return ModerationResult.builder().decision(ModerationDecision.PASS).build();
            } else {
                return ModerationResult.builder().decision(ModerationDecision.MANUAL).build();
            }
        }

        // 3. 计算内容指纹，防重复审核
        String fingerprint = calculateFingerprint(task);

        // 3.1 检查是否有历史记录
        ModerationRecord latestRecord = moderationRecordMapper.selectLatest(
                task.getTargetType().name(), task.getTargetId());

        // 3.2 检查是否有历史记录且状态为已完成，指纹也相同，直接返回历史结果，省token
        if (latestRecord != null && "DONE".equals(latestRecord.getTaskStatus())
                && fingerprint.equals(latestRecord.getContentFingerprint())) {
            log.info("内容未变化，复用历史审核结果 targetId={}", task.getTargetId());
            return ModerationResult.builder()
                    .decision(ModerationDecision.valueOf(latestRecord.getDecision()))
                    .rejectReason(latestRecord.getRejectReason())
                    .build();
        }

        // 4. 执行审核
        ModerationResult textResult = null;
        ModerationResult imageResult = null;

        // 4.1 文本审核
        if (moderationProperties.isTextEnabled()) {
            String textToCheck = buildTextContent(task);
            if (StringUtils.hasText(textToCheck)) {
                textResult = textClient.checkText(String.valueOf(task.getTargetId()), textToCheck);
            }
        }

        // 4.2 图片审核
        if (moderationProperties.isImageEnabled() && task.getImageUrls() != null && !task.getImageUrls().isEmpty()) {
            imageResult = imageClient.checkImages(String.valueOf(task.getTargetId()), task.getImageUrls());
        }

        // 5. 合并结果（优先级：REJECT > MANUAL > ERROR > PASS）
        ModerationResult finalResult = mergeResults(textResult, imageResult);

        // 6. 保存记录
        saveRecord(task, fingerprint, finalResult);

        return finalResult;
    }



    /**
     * 保存审核失败记录
     * @param task 审核任务
     * @param result 审核结果
     */
    @Override
    public void saveFailedRecord(ModerationTaskMessage task, ModerationResult result) {
        String fingerprint = calculateFingerprint(task);
        ModerationRecord record = ModerationRecord.builder()
                .targetType(task.getTargetType().name())
                .targetId(task.getTargetId())
                .provider("ALIYUN")
                .decision(ModerationDecision.ERROR.name())
                .rejectReason(result != null ? result.getRejectReason() : "审核服务不可用")
                .rawResponse(result != null ? result.getRawResponse() : null)
                .contentFingerprint(fingerprint)
                .taskStatus("FAILED")
                .retryCount(task.getRetryCount() != null ? task.getRetryCount() : 0)
                .build();
        moderationRecordMapper.insertOrUpdate(record);
        log.warn("审核失败记录已落库 targetType={}, targetId={}", task.getTargetType(), task.getTargetId());
    }

    /**
     * 获取目标类型的审核配置
     * @param type 目标类型
     * @return 审核配置
     */
    private AliyunModerationProperties.TargetConfig getTargetConfig(ModerationTargetType type) {
        AliyunModerationProperties.Targets targets = moderationProperties.getTargets();
        if (targets == null) return null;
        switch (type) {
            case CONTENT: return targets.getContent();
            case ANSWER: return targets.getAnswer();
            case COMMENT: return targets.getComment();
            default: return null;
        }
    }


    /**
     * 构建待审核的文本内容
     * @param task 审核任务
     * @return 待审核的文本内容
     */

    private String buildTextContent(ModerationTaskMessage task) {
        StringBuilder sb = new StringBuilder();
        if (StringUtils.hasText(task.getTitle())) {
            sb.append(task.getTitle()).append("\n");
        }
        if (StringUtils.hasText(task.getContent())) {
            sb.append(task.getContent());
        }
        return sb.toString();
    }

    /**
     * 计算内容指纹，防重复审核
     * 比如说，标题和内容都相同，但是图片不同，那么指纹就不同
     */
    private String calculateFingerprint(ModerationTaskMessage task) {
        // 1. 标题和内容
        StringBuilder sb = new StringBuilder();
        if (task.getTitle() != null) {
            sb.append(task.getTitle()).append("\n");
        }
        if (task.getContent() != null) {
            sb.append(task.getContent());
        }
        // 2. 图片URL列表
        if (task.getImageUrls() != null && !task.getImageUrls().isEmpty()) {
            List<String> sortedUrls = new ArrayList<>(task.getImageUrls());
            Collections.sort(sortedUrls);
            for (String url : sortedUrls) {
                sb.append(url);
            }
        }
        // 3. 计算MD5指纹(为了防重复审核：即)
        return DigestUtils.md5DigestAsHex(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 合并文本审核和图片审核结果
     */
    private ModerationResult mergeResults(ModerationResult textResult, ModerationResult imageResult) {
        // 优先级：REJECT > MANUAL > ERROR > PASS


        // 文本审核拒绝
        if (textResult != null && textResult.getDecision() == ModerationDecision.REJECT) return textResult;

        // 图片审核拒绝
        if (imageResult != null && imageResult.getDecision() == ModerationDecision.REJECT) return imageResult;

        // 文本审核手动审核
        if (textResult != null && textResult.getDecision() == ModerationDecision.MANUAL) return textResult;

        // 图片审核手动审核
        if (imageResult != null && imageResult.getDecision() == ModerationDecision.MANUAL) return imageResult;

        // 文本审核错误
        if (textResult != null && textResult.getDecision() == ModerationDecision.ERROR) return textResult;

        // 图片审核错误
        if (imageResult != null && imageResult.getDecision() == ModerationDecision.ERROR) return imageResult;

        // 默认通过
        return ModerationResult.builder().decision(ModerationDecision.PASS).build();
    }

    /**
     * 保存审核记录
     */
    private void saveRecord(ModerationTaskMessage task, String fingerprint, ModerationResult result) {
        ModerationRecord record = ModerationRecord.builder()
                .targetType(task.getTargetType().name())
                .targetId(task.getTargetId())
                .provider("ALIYUN")
                .decision(result.getDecision().name())
                .riskLevel(result.getRiskLevel())
                .rejectReason(result.getRejectReason())
                .labels(toLabelsJson(result.getLabels()))
                .rawResponse(result.getRawResponse())
                .contentFingerprint(fingerprint)
                .taskStatus("DONE")
                .retryCount(task.getRetryCount() != null ? task.getRetryCount() : 0)
                .build();

        moderationRecordMapper.insertOrUpdate(record);
    }

    /** MySQL JSON 列要求合法 JSON；List 序列化为 ["a","b"]，空则 null */
    private String toLabelsJson(List<String> labels) {
        if (labels == null || labels.isEmpty()) {
            return null;
        }
        return JSON.toJSONString(labels);
    }
}