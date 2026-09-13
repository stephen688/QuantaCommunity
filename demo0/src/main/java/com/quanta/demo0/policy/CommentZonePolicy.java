package com.quanta.demo0.policy;

import com.quanta.demo0.exception.CommentFailedException;
import org.springframework.stereotype.Component;

/**
 * 评论分区策略类
 * 【设计目的】
 * 统一处理专业区（contentType=2）和生活区（contentType=1）的差异化规则，
 * 避免在 Service 层堆积大量 if-else 判断，提高代码可维护性。
 * 【分区规则】
 * ┌──────────┬──────────────────────┬──────────────────────┐
 * │ 规则     │ 生活区（contentType=1）│ 专业区（contentType=2）│
 * ├──────────┼──────────────────────┼──────────────────────┤
 * │ answerId │ 禁止传               │ 必填                 │
 * │ 字数上限 │ 500 字               │ 1000 字              │
 * │ 图片限额 │ 5 张                 │ 1 张                 │
 * │ 图片允许 │ 是                   │ 否（可选）           │
 * └──────────┴──────────────────────┴──────────────────────┘
 * 【使用示例】
 * <pre>
 * // 1. 校验 answerId
 * commentZonePolicy.validateAnswerId(contentType, answerId);
 * // 2. 获取字数上限
 * int maxLength = commentZonePolicy.getMaxLength(contentType);
 * // 3. 获取图片限额
 * int maxImages = commentZonePolicy.getMaxImages(contentType);
 * </pre>
 * @author Quanta Team
 * @since 2026-05-01
 */
@Component
public class CommentZonePolicy {

    /**
     * 生活区评论字数上限
     */
    private static final int LIFE_MAX_LENGTH = 500;

    /**
     * 专业区评论字数上限（允许更深入的讨论）
     */
    private static final int PROFESSIONAL_MAX_LENGTH = 1000;

    /**
     * 生活区评论配图限额
     */
    private static final int LIFE_MAX_IMAGES = 5;

    /**
     * 专业区评论配图限额（弱化图片，偏纯文本讨论）
     */
    private static final int PROFESSIONAL_MAX_IMAGES = 1;

    /**
     * 生活区 contentType 常量
     */
    private static final int CONTENT_TYPE_LIFE = 1;

    /**
     * 专业区 contentType 常量
     */
    private static final int CONTENT_TYPE_PROFESSIONAL = 2;

    /**
     * 校验 answerId 是否合法
     * 【校验规则】
     * - 专业区（contentType=2）：answerId 必填，否则抛异常
     * - 生活区（contentType=1）：answerId 禁止传，否则抛异常
     * 【使用场景】
     * 在 CommentServiceImpl.sendComment() 和 commentPage() 中调用，
     * 确保专业区和生活区的 answerId 传递规则正确。
     * @param contentType 内容类型（1=生活区，2=专业区）
     * @param answerId    回答 ID（专业区必填，生活区禁止传）
     * @throws CommentFailedException 当 answerId 不符合分区规则时抛出
     */
    public void validateAnswerId(Integer contentType, Long answerId) {
        // 专业区：answerId 必填
        if (contentType == CONTENT_TYPE_PROFESSIONAL && answerId == null) {
            throw new CommentFailedException("专业区评论必须指定回答 ID（answerId）");
        }

        // 生活区：answerId 禁止传
        if (contentType == CONTENT_TYPE_LIFE && answerId != null) {
            throw new CommentFailedException("生活区评论不支持回答 ID（answerId）");
        }
    }

    /**
     * 获取评论字数上 限
     * 【规则说明】
     * - 生活区：500 字（轻互动，短评论为主）
     * - 专业区：1000 字（深度讨论，允许长文本）
     * 【使用场景】
     * 在 CommentServiceImpl.sendComment() 中校验评论内容长度时调用。
     * @param contentType 内容类型（1=生活区，2=专业区）
     * @return 字数上限（生活区 500，专业区 1000）
     */
    public int getMaxLength(Integer contentType) {
        return contentType == CONTENT_TYPE_PROFESSIONAL
                ? PROFESSIONAL_MAX_LENGTH
                : LIFE_MAX_LENGTH;
    }

    /**
     * 获取评论配图限额
     * 【规则说明】
     * - 生活区：5 张（图片为主，小红书风格）
     * - 专业区：1 张（弱化图片，偏纯文本讨论）
     * 【使用场景】
     * 在 CommentServiceImpl.sendComment() 中校验评论图片数量时调用。
     * @param contentType 内容类型（1=生活区，2=专业区）
     * @return 图片限额（生活区 5 张，专业区 1 张）
     */
    public int getMaxImages(Integer contentType) {
        return contentType == CONTENT_TYPE_PROFESSIONAL
                ? PROFESSIONAL_MAX_IMAGES
                : LIFE_MAX_IMAGES;
    }

    /**
     * 判断是否允许评论配图
     * 【规则说明】
     * - 生活区：允许配图（最多 5 张）
     * - 专业区：允许配图（最多 1 张，后续可配置为禁止）
     * 【使用场景】
     * 在 CommentServiceImpl.sendComment() 中判断是否需要处理图片上传。
     * @param contentType 内容类型（1=生活区，2=专业区）
     * @return true=允许配图，false=禁止配图
     */
    public boolean allowImages(Integer contentType) {
        // 目前两个区都允许配图，只是限额不同
        // 后续如果专业区需要禁止配图，可改为：
        // return contentType == CONTENT_TYPE_LIFE;
        return true;
    }

    /**
     * 判断是否为专业区
     * 【使用场景】
     * 在需要区分专业区和生活区的逻辑中调用，提高代码可读性。
     * @param contentType 内容类型（1=生活区，2=专业区）
     * @return true=专业区，false=生活区
     */
    public boolean isProfessional(Integer contentType) {
        return contentType == CONTENT_TYPE_PROFESSIONAL;
    }

    /**
     * 判断是否为生活区
     * 【使用场景】
     * 在需要区分生活区和专业区的逻辑中调用，提高代码可读性。
     * @param contentType 内容类型（1=生活区，2=专业区）
     * @return true=生活区，false=专业区
     */
    public boolean isLife(Integer contentType) {
        return contentType == CONTENT_TYPE_LIFE;
    }
}