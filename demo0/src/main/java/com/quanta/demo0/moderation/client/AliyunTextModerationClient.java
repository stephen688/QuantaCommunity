package com.quanta.demo0.moderation.client;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.aliyun.green20220302.Client;
import com.aliyun.green20220302.models.TextModerationRequest;
import com.aliyun.green20220302.models.TextModerationResponse;
import com.aliyun.green20220302.models.TextModerationResponseBody;
import com.aliyun.teautil.models.RuntimeOptions;
import com.quanta.demo0.moderation.enums.ModerationDecision;
import com.quanta.demo0.moderation.result.ModerationResult;
import com.quanta.demo0.moderation.properties.AliyunModerationProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Collections;

/**
 * 阿里云文本审核客户端：把一段文本交给阿里云内容安全（green-cip）机审，
 * 并把 SDK 返回翻译成统一的 {@link ModerationResult}（PASS / REJECT / MANUAL / ERROR）。
 *
 * 链路位置：发布时敏感词初筛（SensitiveWordChecker）通过的内容，经 Outbox + MQ
 * 到达消费端后，由 ContentModerationServiceImpl#doModerate 调用本类做真正的云上机审。
 *
 * ============================================================
 * 【为什么要封一层 Client，而不是让 Service 直接调 SDK？】
 * ============================================================
 * 1. 收口配置与翻译规则：service 名称（comment_detection）、参数拼装、
 *    双层状态码校验、riskLevel → 审核决策的映射全部留在这里。
 *    将来换审核供应商，只需新写一个同样返回 ModerationResult 的客户端，
 *    Service 与消费工作流一行不用改。
 * 2. 统一兜底语义：网络异常、HTTP 非 200、业务 code 非 200 一律翻译成
 *    ERROR 决策返回，不向调用方抛异常——重试还是死信由上层工作流决定
 *    （见 ModerationWorkflowServiceImpl#handleProcessingFailure）。
 * **一句话：Client 只负责"翻译"，不负责"善后"。**
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AliyunTextModerationClient {

    /** 与控制台「使用中」场景一致；Pro 版需账号单独开通 comment_detection_pro */
    private static final String TEXT_SERVICE = "comment_detection";

    private final Client aliyunGreenClient;
    private final AliyunModerationProperties moderationProperties;

    /**
     * 送审一段文本，返回统一的审核结论。
     *
     * @param dataId 送审业务 ID（调用方传的是帖子/回答/评论的 targetId），
     *               阿里云用它关联请求，便于在控制台按业务回查
     * @param text   待审文本（标题 + 正文，见 ContentModerationServiceImpl#buildTextContent）
     *
     * 【边界】空文本不调云 API，直接 PASS——省一次调用费用。
     * 【坑】所有异常都被吞掉并翻译成 ERROR 决策返回，调用方拿不到异常对象，
     *      排障只能靠 rejectReason、日志和落库的 rawResponse。
     */
    public ModerationResult checkText(String dataId, String text) {
        if (!StringUtils.hasText(text)) {
            return ModerationResult.builder().decision(ModerationDecision.PASS).build();
        }

        try {
            // 阿里云的接口约定：业务参数统一塞进 serviceParameters 这个 JSON 字符串
            JSONObject serviceParameters = new JSONObject();
            serviceParameters.put("content", text);
            serviceParameters.put("dataId", dataId);

            TextModerationRequest request = new TextModerationRequest()
                    .setService(TEXT_SERVICE)
                    .setServiceParameters(serviceParameters.toJSONString());

            RuntimeOptions runtime = new RuntimeOptions();
            TextModerationResponse response = aliyunGreenClient.textModerationWithOptions(request, runtime);

            // 第一层校验：HTTP 传输层状态码（≠200 说明网关/鉴权/限流层面就出了问题）
            if (response == null || response.getStatusCode() == null || response.getStatusCode() != 200) {
                return ModerationResult.builder()
                        .decision(ModerationDecision.ERROR)
                        .rejectReason("HTTP status: " + (response != null ? response.getStatusCode() : null))
                        .build();
            }

            // 第二层校验：HTTP 200 只代表传输层通了，业务是否成功还要看 body.code 和 data
            TextModerationResponseBody body = response.getBody();
            if (body == null || body.getCode() == null || body.getCode() != 200 || body.getData() == null) {
                return ModerationResult.builder()
                        .decision(ModerationDecision.ERROR)
                        .rejectReason(body != null ? body.getMessage() : "Empty Response")
                        .rawResponse(body != null ? JSON.toJSONString(body) : null)
                        .build();
            }

            TextModerationResponseBody.TextModerationResponseBodyData data = body.getData();
            // 阿里云把标签放 labels，把一段"字符串化的 JSON"放 reason（riskLevel/riskTips 都藏在里面）
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

    /**
     * 把 SDK 响应体翻译成 ModerationResult。
     *
     * 【边界】labels 为空视为无风险直接 PASS——阿里云"未命中风险"时不返回标签。
     * rawResponse 保留整个响应体 JSON，最终随记录落库（tb_moderation_record.raw_response，MEDIUMTEXT）供排障。
     */
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

    /**
     * 从 reason（它本身是一段"字符串化的 JSON"）里抠出 riskLevel（high/medium/low）。
     * 【坑】reason 缺失或解析失败时兜底返回 "medium"（疑似档），
     * 而不是当作无风险——宁可多转人工，不可漏放违规。
     */
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

    /**
     * riskLevel → 内部审核决策的翻译表（本类最核心的规则，开关来自 AliyunModerationProperties）。
     *
     * - high    ：auto-reject-enabled=true 时直接 REJECT，否则转人工 MANUAL；
     * - medium  ：manual-on-suspect=true 时转人工 MANUAL，否则 REJECT；
     * - low     ：PASS；
     * - 空等级  ：按"疑似"处理，MANUAL 还是 REJECT 由 manual-on-suspect 决定；
     * - 未知等级：一律 MANUAL（人工兜底，绝不自动通过）。
     *
     * 与图片客户端的差别：文本按 riskLevel 分档决策，图片按 confidence 数值过阈值决策。
     */
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

    /**
     * 拼 REJECT 时给人看的驳回原因：优先取 reason 里的 riskTips（云上给出的人话描述），
     * 取不到则退回风险标签串 labels，保证提示语永远不为空。
     */
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
