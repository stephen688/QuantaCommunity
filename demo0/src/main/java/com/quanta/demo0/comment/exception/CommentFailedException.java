package com.quanta.demo0.comment.exception;

/**
 * 评论域业务异常：评论发布 / 删除 / 审核校验不通过时抛出。
 *
 * ============================================================
 * 【为什么是 RuntimeException 且不继承 BaseException？】
 * ============================================================
 * 继承 RuntimeException 而非受检异常，是为了不打断 @Transactional Service
 * 方法的签名，同时**Spring 默认只对 RuntimeException 回滚事务**——评论校验失败
 * （如"父级评论不存在"）时，已写入的评论 / 图片 / Outbox 行必须一起回滚。
 * 它没有走 platform 的 BaseException 体系，而是直接继承 RuntimeException；
 * 兜底逻辑在 GlobalExceptionHandler 的 @ExceptionHandler(CommentFailedException.class)：
 * 统一转成 400 + 异常 message，前端直接展示给用户。
 * 一个例外值得注意：CommentEventProducer 里部分场景抛的是 content 包的
 * ContentFailedException（同样映射 400），两者语义等价、可互换。
 */
public class CommentFailedException extends RuntimeException {
    public CommentFailedException(String message) {
        super(message);
    }
}
