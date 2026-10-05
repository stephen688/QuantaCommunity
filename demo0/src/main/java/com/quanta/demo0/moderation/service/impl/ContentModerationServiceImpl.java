package com.quanta.demo0.moderation.service.impl;


/**
 * 内容审核服务实现类
 * - 开关控制 ：总开关、目标类型开关、兜底策略
 * - 指纹防重 ：计算内容 MD5 指纹，避免重复审核浪费费用
 * - 流程编排 ：协调文本审核和图片审核客户端
 * - 结果合并 ：按优先级（REJECT > MANUAL > ERROR > PASS）合并多模态审核结果
 * - 记录持久化 ：将审核结果保存到数据库，方便追溯
 * - 失败处理 ：审核服务异常时记录错误信息
 *
 * ============================================================
 * 【moderate 的四道闸门——顺序本身就是设计】
 * ============================================================
 * 1. bot 发布者（publisherUserId == quantabot.bot-user-id，yml 中为 10000）
 *    强制机审，排在所有开关之前——bot 内容不允许靠关开关漏过去（C-6 契约）；
 * 2. 总开关 quanta.moderation.enabled：关 → 直接 PASS；
 * 3. 目标开关 quanta.moderation.targets.<type>.enabled：关 → 走 disabled-policy 兜底，
 *    APPROVED=视为通过（yml 里 comment 就是这么配的，省机审费用），
 *    其余取值（yml 里 content/answer 是 PENDING，配置缺失也按此）→ 返回 MANUAL 转人工；
 * 4. 指纹复用 + 云机审（doModerate）。
 * **每一道闸门都在给阿里云 API 省钱：能不调云就不调云**。
 * （注意：配置里的 cost-control-enabled 目前没有任何代码读取，
 * 代码中真正生效的省费手段是闸门 2/3 的短路 + 闸门 4 的指纹复用。）
 *
 * ============================================================
 * 【指纹复用：第二次审同样的内容不花钱】
 * ============================================================
 * 每次机审前计算内容指纹（标题+正文+排序后图片 URL 的 MD5），与该目标
 * 最近一条 DONE 记录比较，相同则直接复用历史 decision——
 * **防的是重复扣费，同时给消费端重试提供了天然幂等**（重试的消息内容
 * 不变，指纹相同，第二次起不再调云）。只认 taskStatus=DONE：
 * 上次没审成（FAILED）不算数，必须重新机审，见 doModerate。
 *
 * ============================================================
 * 【审核记录为什么独立成表（tb_moderation_record）】
 * ============================================================
 * 一是审计追溯：云 API 原始响应 rawResponse 原样入库，事后可查"AI 当时看到了什么"；
 * 二是它本身就是指纹复用的数据源（没有这张表就没有省费短路）；
 * 三是它把 MANUAL/FAILED 的证据交给管理端——AdminModerationService 查询它，
 * 人工审核据此裁决。写入走 insertOrUpdate：唯一键 (target_type, target_id, provider)
 * 上 ON DUPLICATE KEY UPDATE（见 ModerationRecordMapper.xml），
 * **同一目标永远只有一条最新记录，重复写入天然幂等**。
 */

import com.alibaba.fastjson.JSON;
import com.quanta.demo0.moderation.enums.ModerationDecision;
import com.quanta.demo0.moderation.enums.ModerationTargetType;
import com.quanta.demo0.moderation.entity.ModerationRecord;
import com.quanta.demo0.moderation.mapper.ModerationRecordMapper;

import com.quanta.demo0.moderation.client.AliyunImageModerationClient;
import com.quanta.demo0.moderation.client.AliyunTextModerationClient;
import com.quanta.demo0.moderation.result.ModerationResult;
import com.quanta.demo0.moderation.mq.message.ModerationTaskMessage;
import com.quanta.demo0.moderation.properties.AliyunModerationProperties;
import com.quanta.demo0.platform.security.properties.QuantabotProperties;
import com.quanta.demo0.moderation.service.ContentModerationService;
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

    /** C-6：bot 账号配置（botUserId 判定 bot 来源内容强制机审） */
    @Autowired
    private QuantabotProperties quantabotProperties;

    /**
     * 机审入口（四道闸门顺序见类注释）。
     * 【坑】bot 判定必须在总开关之前——若先判开关，bot 二审会被运维关开关的动作
     * 一起短路，bot 协作生态（C-6）就漏了。
     */
    @Override
    public ModerationResult moderate(ModerationTaskMessage task) {
        // C-6：bot 来源内容强制机审——在总开关与目标开关之前判定，消费端不短路 bot 的二审
        if (isBotPublisher(task)) {
            log.info("bot 来源内容强制机审 targetId={}, publisherUserId={}",
                    task.getTargetId(), task.getPublisherUserId());
            return doModerate(task);
        }

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

        return doModerate(task);
    }

    /** C-6：任务发布者是否 bot 系统账号（publisherUserId 由写库入口从评论实体带出，可信）。 */
    private boolean isBotPublisher(ModerationTaskMessage task) {
        return task.getPublisherUserId() != null
                && task.getPublisherUserId().equals(quantabotProperties.getBotUserId());
    }

    /**
     * 实际机审执行（指纹去重 + 文本/图片审核），供 moderate 开关判定通过后调用。
     *
     * 【失败如何上抛】文本/图片客户端任何一次调用失败都会以 ERROR 决策返回
     * （见 AliyunTextModerationClient / AliyunImageModerationClient 的 catch 分支），
     * 合并后由 ModerationWorkflowServiceImpl.processAcquiredMessage 触发重试——
     * 本方法自己不重试，重试是消费编排层的事。
     */
    private ModerationResult doModerate(ModerationTaskMessage task) {
        // 3. 计算内容指纹，防重复审核
        String fingerprint = calculateFingerprint(task);

        // 3.1 检查是否有历史记录
        ModerationRecord latestRecord = moderationRecordMapper.selectLatest(
                task.getTargetType().name(), task.getTargetId());

        // 3.2 检查是否有历史记录且状态为已完成，指纹也相同，直接返回历史结果，省token
        // 【只认 DONE】taskStatus=FAILED 的记录（重试耗尽留下的）不参与复用——
        // 上次根本没审成，复用它等于把"失败"当"通过"，所以必须重新调云
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
     *
     * 【与 saveRecord 的差异】decision 固定 ERROR、taskStatus=FAILED。
     * 只有重试耗尽才会走到这（调用方 ModerationWorkflowServiceImpl.handleProcessingFailure），
     * FAILED 记录不参与指纹复用；exception 路径下 result 为 null，
     * 落库原因退化为"审核服务不可用"。
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
     *
     * 【标题和正文拼成一段送审】云侧没有"字段"概念，只看一整段文本；
     * 标题后补一个换行避免"标题末字+正文首字"意外连成一个新词。
     * 两者都为空时返回空串，调用方 hasText 判空后直接跳过文本审核。
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
     *
     * 【图片 URL 先排序再拼接】同一组图换个顺序算同一条内容——
     * 作者仅调整图片顺序重新提交时不会二次扣费。指纹是 MD5 十六进制串，
     * 与审核记录表 content_fingerprint(VARCHAR(64)) 对齐。
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
     *
     * 【优先级 REJECT > MANUAL > ERROR > PASS 的真实含义】
     * 任一模态给出了业务结论（驳回/转人工），另一模态的调用失败（ERROR）
     * 就不再触发整体重试——**重试只为"完全没有业务结论"的调用保留**。
     * 两个模态都没执行（如无图片且文本为空）时兜底 PASS，不会无脑拦截。
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
     *
     * 【坑】labels 必须转 JSON 字符串（表列是 MySQL JSON 类型，要求合法 JSON，
     * 见 toLabelsJson）；retryCount 取消息自带的重试计数落库，
     * 方便管理端判断这条结论是"第一次审"还是"重试后的结论"。
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
