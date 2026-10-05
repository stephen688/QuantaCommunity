package com.quanta.demo0.platform.web.idempotency.utils;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.quanta.demo0.platform.web.idempotency.exception.SubmissionException;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Iterator;
import java.util.Map;
import java.util.TreeMap;

/**
 * HTTP 请求摘要工具。
 *
 * 职责：固定 JSON 对象字段顺序、保留数组顺序后计算 SHA-256；边界：不把提交 Token 纳入摘要。
 */
public final class SubmissionRequestHasher {

    private SubmissionRequestHasher() {
    }

    /**
     * 计算请求内容的稳定摘要。
     *
     * @param request 不包含 Idempotency-Key 的领域请求 DTO
     * @param objectMapper 现有 Spring Jackson 编排器
     * @return 小写十六进制 SHA-256
     */
    public static String sha256(Object request, ObjectMapper objectMapper) {
        try {
            JsonNode normalized = normalize(objectMapper.convertValue(request, JsonNode.class));
            byte[] bytes = objectMapper.writeValueAsBytes(normalized);
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                result.append(String.format("%02x", value));
            }
            return result.toString();
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw SubmissionException.badRequest("提交参数无法生成摘要");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    private static JsonNode normalize(JsonNode node) {
        if (node == null || node.isValueNode() || node.isNull()) {
            return node;
        }
        if (node.isArray()) {
            ArrayNode array = (ArrayNode) node;
            ArrayNode normalized = JsonNodeFactory.instance.arrayNode();
            for (JsonNode child : array) {
                normalized.add(normalize(child));
            }
            return normalized;
        }
        ObjectNode object = (ObjectNode) node;
        ObjectNode normalized = JsonNodeFactory.instance.objectNode();
        TreeMap<String, JsonNode> sorted = new TreeMap<>();
        Iterator<Map.Entry<String, JsonNode>> fields = object.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            sorted.put(field.getKey(), normalize(field.getValue()));
        }
        sorted.forEach(normalized::set);
        return normalized;
    }
}
