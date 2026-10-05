package com.quanta.demo0.feed.dto;

import com.quanta.demo0.content.exception.ContentFailedException;

import java.util.regex.Pattern;

/**
 * 推荐协议的可信访问主体。
 *
 * <p>登录主体只使用服务端认证得到的 userId；游客主体使用客户端持久化的
 * UUID v4，但该标识只作为曝光去重分区键，不能替代鉴权。</p>
 */
public record RecommendVisitor(Long userId, String guestId) {

    private static final Pattern UUID_V4_PATTERN = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$");

    /**
     * 规范化 HTTP 门面传入的主体。
     *
     * @param userId 服务端认证用户 ID；非空时忽略 guestId
     * @param guestId 未登录游客 UUID v4
     * @return 绑定到一个主体的访问者
     * @throws ContentFailedException 主体缺失、用户 ID 非正数或游客标识不是 UUID v4
     */
    public static RecommendVisitor from(Long userId, String guestId) {
        if (userId != null) {
            if (userId <= 0) {
                throw new ContentFailedException("登录用户标识无效");
            }
            return new RecommendVisitor(userId, null);
        }
        if (guestId == null || !UUID_V4_PATTERN.matcher(guestId).matches()) {
            throw new ContentFailedException("游客标识格式错误");
        }
        return new RecommendVisitor(null, guestId);
    }

    /**
     * 生成 Redis 访问主体键。
     *
     * @return `u:{userId}` 或 `g:{guestId}`
     * @throws ContentFailedException 没有任何主体标识
     */
    public String actorKey() {
        if (userId != null) {
            if (userId <= 0) {
                throw new ContentFailedException("登录用户标识无效");
            }
            return "u:" + userId;
        }
        if (guestId == null || !UUID_V4_PATTERN.matcher(guestId).matches()) {
            throw new ContentFailedException("游客标识格式错误");
        }
        return "g:" + guestId;
    }
}
