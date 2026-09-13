package com.quanta.demo0.modertion.client;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.aliyun.green20220302.Client;
import com.aliyun.green20220302.models.ImageModerationRequest;
import com.aliyun.green20220302.models.ImageModerationResponse;
import com.aliyun.green20220302.models.ImageModerationResponseBody;
import com.aliyun.teautil.models.RuntimeOptions;
import com.quanta.demo0.annotation.ModerationDecision;
import com.quanta.demo0.modertion.result.ModerationResult;
import com.quanta.demo0.properties.AliyunModerationProperties;
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
 */

@Slf4j
@Component
@RequiredArgsConstructor
public class AliyunImageModerationClient {

    /** 与控制台在线检测/结果查询一致（VL 基线）
     * ；classic baselineCheck 未开通时会 service is invalid
     * */
    private static final String IMAGE_SERVICE = "baselineCheckByVL";
    private static final float REJECT_CONFIDENCE = 70F;

    private final Client aliyunGreenClient;
    private final AliyunModerationProperties moderationProperties;

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
