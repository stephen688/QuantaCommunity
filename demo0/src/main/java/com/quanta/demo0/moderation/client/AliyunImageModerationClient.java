package com.quanta.demo0.moderation.client;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.aliyun.green20220302.Client;
import com.aliyun.green20220302.models.ImageModerationRequest;
import com.aliyun.green20220302.models.ImageModerationResponse;
import com.aliyun.green20220302.models.ImageModerationResponseBody;
import com.aliyun.teautil.models.RuntimeOptions;
import com.quanta.demo0.moderation.enums.ModerationDecision;
import com.quanta.demo0.moderation.result.ModerationResult;
import com.quanta.demo0.moderation.properties.AliyunModerationProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;


/**
 * 阿里云图片审核客户端(与文本审核客户端分开，
 * 原因是图片审核需要上传图片到阿里云存储，而文本审核则不需要)
 *
 * ============================================================
 * 【图片审核比文本审核多出来的两件事】
 * ============================================================
 * 1. 多图聚合：一条内容可能带多张图，checkImages 逐张送审后按
 *    REJECT > MANUAL > PASS 聚合成一个结论；任何一张送审出错，
 *    整批直接返回 ERROR 交给上层重试，绝不"部分通过"。
 * 2. 置信度门槛：文本靠 riskLevel 分档，图片靠 confidence 数值——
 *    只有达到 {@link #REJECT_CONFIDENCE} 才算"实锤"，否则一律按疑似处理。
 */

@Slf4j
@Component
@RequiredArgsConstructor
public class AliyunImageModerationClient {

    /** 与控制台在线检测/结果查询一致（VL 基线）
     * ；classic baselineCheck 未开通时会 service is invalid
     * */
    private static final String IMAGE_SERVICE = "baselineCheckByVL";
    /** 单张图 confidence 达到该阈值才算"实锤违规"（否则只算疑似，转人工） */
    private static final float REJECT_CONFIDENCE = 70F;

    private final Client aliyunGreenClient;
    private final AliyunModerationProperties moderationProperties;

    /**
     * 逐张送审一组图片，聚合成一个整体结论。
     *
     * @param dataIdPrefix 业务 ID 前缀（调用方传 targetId）；每张图的实际 dataId 为
     *                     "前缀_下标"，让阿里云端能区分同一条内容的多张图
     *
     * 【聚合规则】任一张 REJECT → 整体 REJECT；否则任一张 MANUAL → 整体 MANUAL；
     * 都没有才 PASS。【坑】任何一张送审出错（ERROR）立即短路返回该 ERROR，
     * 剩余图片不再送审——这轮审核视为"没审成"，由上层工作流决定重试。
     */
    public ModerationResult checkImages(String dataIdPrefix, List<String> imageUrls) {
        if (CollectionUtils.isEmpty(imageUrls)) {
            return ModerationResult.builder().decision(ModerationDecision.PASS).build();
        }

        boolean hasBlock = false;
        boolean hasReview = false;
        List<String> allLabels = new ArrayList<>();
        List<String> rawResponses = new ArrayList<>();

        for (int i = 0; i < imageUrls.size(); i++) {
            String imageUrl = imageUrls.get(i);
            if (!StringUtils.hasText(imageUrl)) {
                continue;
            }

            ModerationResult singleResult = checkSingleImage(dataIdPrefix + "_" + i, imageUrl);
            if (singleResult.getDecision() == ModerationDecision.ERROR) {
                return singleResult;
            }
            if (singleResult.getLabels() != null) {
                allLabels.addAll(singleResult.getLabels());
            }
            if (StringUtils.hasText(singleResult.getRawResponse())) {
                rawResponses.add(singleResult.getRawResponse());
            }
            if (singleResult.getDecision() == ModerationDecision.REJECT) {
                hasBlock = true;
            } else if (singleResult.getDecision() == ModerationDecision.MANUAL) {
                hasReview = true;
            }
        }

        ModerationDecision decision;
        if (hasBlock) {
            decision = ModerationDecision.REJECT;
        } else if (hasReview) {
            decision = ModerationDecision.MANUAL;
        } else {
            decision = ModerationDecision.PASS;
        }

        return ModerationResult.builder()
                .decision(decision)
                .rejectReason(decision == ModerationDecision.REJECT ? "图片包含违规内容" : null)
                .labels(allLabels)
                .rawResponse(String.join("\n", rawResponses))
                .build();
    }

    /** 送审单张图片：拼参数 → 调 SDK → 双层状态码校验 → 翻译结论，与文本客户端的套路一致 */
    private ModerationResult checkSingleImage(String dataId, String imageUrl) {
        try {
            JSONObject serviceParameters = new JSONObject();
            serviceParameters.put("imageUrl", imageUrl);
            serviceParameters.put("dataId", dataId);

            ImageModerationRequest request = new ImageModerationRequest()
                    .setService(IMAGE_SERVICE)
                    .setServiceParameters(serviceParameters.toJSONString());

            RuntimeOptions runtime = new RuntimeOptions();
            ImageModerationResponse response = aliyunGreenClient.imageModerationWithOptions(request, runtime);

            if (response == null || response.getStatusCode() == null || response.getStatusCode() != 200) {
                return ModerationResult.builder()
                        .decision(ModerationDecision.ERROR)
                        .rejectReason("HTTP status: " + (response != null ? response.getStatusCode() : null))
                        .build();
            }

            ImageModerationResponseBody body = response.getBody();
            if (body == null || body.getCode() == null || body.getCode() != 200) {
                return ModerationResult.builder()
                        .decision(ModerationDecision.ERROR)
                        .rejectReason(body != null ? body.getMsg() : "Empty Response")
                        .rawResponse(body != null ? JSON.toJSONString(body) : null)
                        .build();
            }

            return mapImageResult(body, JSON.toJSONString(body));
        } catch (Exception e) {
            log.error("图片审核异常 dataId={}, url={}", dataId, imageUrl, e);
            return ModerationResult.builder()
                    .decision(ModerationDecision.ERROR)
                    .rejectReason(e.getMessage())
                    .build();
        }
    }

    /**
     * 翻译单张图的响应体：遍历 result 列表，按 confidence 分成"实锤"与"疑似"两桶。
     *
     * 【坑】阿里云用 "nonLabel" 表示"未命中任何风险标签"，要显式跳过；
     * 全部是 nonLabel 时 labels 为空，直接 PASS。
     * 【决策规则】有实锤（confidence ≥ 70）且 auto-reject-enabled=true → REJECT；
     * 有实锤但关了自动拒绝、或只有疑似 → manual-on-suspect=true 转 MANUAL，否则 REJECT；
     * 什么都没有 → PASS。
     */
    private ModerationResult mapImageResult(ImageModerationResponseBody body, String raw) {
        ImageModerationResponseBody.ImageModerationResponseBodyData data = body.getData();
        if (data == null || CollectionUtils.isEmpty(data.getResult())) {
            return ModerationResult.builder()
                    .decision(ModerationDecision.PASS)
                    .rawResponse(raw)
                    .build();
        }

        boolean hasBlock = false;
        boolean hasReview = false;
        List<String> labels = new ArrayList<>();

        for (ImageModerationResponseBody.ImageModerationResponseBodyDataResult result : data.getResult()) {
            String label = result.getLabel();
            // nonLabel = "没有命中风险标签"，不是真实标签，跳过不统计
            if (!StringUtils.hasText(label) || "nonLabel".equalsIgnoreCase(label)) {
                continue;
            }
            labels.add(label);

            Float confidence = result.getConfidence();
            if (confidence != null && confidence >= REJECT_CONFIDENCE) {
                hasBlock = true;
            } else {
                hasReview = true;
            }
        }

        if (labels.isEmpty()) {
            return ModerationResult.builder()
                    .decision(ModerationDecision.PASS)
                    .rawResponse(raw)
                    .build();
        }

        ModerationDecision decision;
        if (hasBlock && moderationProperties.isAutoRejectEnabled()) {
            decision = ModerationDecision.REJECT;
        } else if (hasBlock || hasReview) {
            decision = moderationProperties.isManualOnSuspect()
                    ? ModerationDecision.MANUAL
                    : ModerationDecision.REJECT;
        } else {
            decision = ModerationDecision.PASS;
        }

        return ModerationResult.builder()
                .decision(decision)
                .rejectReason(decision == ModerationDecision.REJECT ? "图片包含违规内容：" + String.join(",", labels) : null)
                .labels(labels)
                .rawResponse(raw)
                .build();
    }
}
