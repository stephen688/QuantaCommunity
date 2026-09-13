package com.quanta.demo0.modertion.client;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.aliyun.green20220302.Client;
import com.aliyun.green20220302.models.TextModerationRequest;
import com.aliyun.green20220302.models.TextModerationResponse;
import com.aliyun.green20220302.models.TextModerationResponseBody;
import com.aliyun.teautil.models.RuntimeOptions;
import com.quanta.demo0.annotation.ModerationDecision;
import com.quanta.demo0.modertion.result.ModerationResult;
import com.quanta.demo0.properties.AliyunModerationProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Collections;

@Slf4j
@Component
@RequiredArgsConstructor
public class AliyunTextModerationClient {

    /** 与控制台「使用中」场景一致；Pro 版需账号单独开通 comment_detection_pro */
    private static final String TEXT_SERVICE = "comment_detection";

    private final Client aliyunGreenClient;
    private final AliyunModerationProperties moderationProperties;

    public ModerationResult checkText(String dataId, String text) {
        if (!StringUtils.hasText(text)) {
            return ModerationResult.builder().decision(ModerationDecision.PASS).build();
        }

        try {
            JSONObject serviceParameters = new JSONObject();
            serviceParameters.put("content", text);
            serviceParameters.put("dataId", dataId);

            TextModerationRequest request = new TextModerationRequest()
                    .setService(TEXT_SERVICE)
                    .setServiceParameters(serviceParameters.toJSONString());

            RuntimeOptions runtime = new RuntimeOptions();
            TextModerationResponse response = aliyunGreenClient.textModerationWithOptions(request, runtime);

            if (response == null || response.getStatusCode() == null || response.getStatusCode() != 200) {
                return ModerationResult.builder()
                        .decision(ModerationDecision.ERROR)
                        .rejectReason("HTTP status: " + (response != null ? response.getStatusCode() : null))
                        .build();
            }

            TextModerationResponseBody body = response.getBody();
            if (body == null || body.getCode() == null || body.getCode() != 200 || body.getData() == null) {
                return ModerationResult.builder()
                        .decision(ModerationDecision.ERROR)
                        .rejectReason(body != null ? body.getMessage() : "Empty Response")
                        .rawResponse(body != null ? JSON.toJSONString(body) : null)
                        .build();
            }

            TextModerationResponseBody.TextModerationResponseBodyData data = body.getData();
            String labels = data.getLabels();
            String reason = data.getReason();

            log.info("文本审核结果 dataId={}, labels={}, reason={}", dataId, labels, reason);

            return mapTextResult(labels, reason, JSON.toJSONString(body));
        } catch (Exception e) {
            log.error("文本审核异常 dataId={}", dataId, e);
            return ModerationResult.builder()
                    .decision(ModerationDecision.ERROR)
                    .rejectReason(e.getMessage())
                    .build();
        }
    }

    private ModerationResult mapTextResult(String labels, String reason, String raw) {
        if (!StringUtils.hasText(labels)) {
            return ModerationResult.builder()
                    .decision(ModerationDecision.PASS)
                    .rawResponse(raw)
                    .build();
        }

        String riskLevel = parseRiskLevel(reason);
        ModerationDecision decision = mapRiskLevel(riskLevel);

        return ModerationResult.builder()
                .decision(decision)
                .rejectReason(decision == ModerationDecision.REJECT ? buildRejectReason(labels, reason) : null)
                .labels(Collections.singletonList(labels))
                .riskLevel(riskLevel)
                .rawResponse(raw)
                .build();
    }

    private String parseRiskLevel(String reason) {
        if (!StringUtils.hasText(reason)) {
            return "medium";
        }
        try {
            JSONObject reasonJson = JSON.parseObject(reason);
            return reasonJson.getString("riskLevel");
        } catch (Exception e) {
            log.warn("解析文本审核 reason 失败: {}", reason);
            return "medium";
        }
    }

    private ModerationDecision mapRiskLevel(String riskLevel) {
        if (!StringUtils.hasText(riskLevel)) {
            return moderationProperties.isManualOnSuspect()
                    ? ModerationDecision.MANUAL
                    : ModerationDecision.REJECT;
        }
        return switch (riskLevel.toLowerCase()) {
            case "high" -> moderationProperties.isAutoRejectEnabled()
                    ? ModerationDecision.REJECT
                    : ModerationDecision.MANUAL;
            case "medium" -> moderationProperties.isManualOnSuspect()
                    ? ModerationDecision.MANUAL
                    : ModerationDecision.REJECT;
            case "low" -> ModerationDecision.PASS;
            default -> ModerationDecision.MANUAL;
        };
    }

    private String buildRejectReason(String labels, String reason) {
        if (!StringUtils.hasText(reason)) {
            return "内容包含违规风险：" + labels;
        }
        try {
            JSONObject reasonJson = JSON.parseObject(reason);
            String riskTips = reasonJson.getString("riskTips");
            if (StringUtils.hasText(riskTips)) {
                return "内容包含违规风险：" + riskTips;
            }
        } catch (Exception ignored) {
            // fall through
        }
        return "内容包含违规风险：" + labels;
    }
}
