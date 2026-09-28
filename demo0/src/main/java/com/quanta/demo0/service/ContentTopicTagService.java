package com.quanta.demo0.service;

/**
 * 内容主题标签服务。
 *
 * 职责：为当前可见帖子异步生成受控主题标签，或将未处理存量帖子按 ID 游标入队；
 * 边界：不修改审核状态，不直接发送 RabbitMQ，外部模型失败由消息消费者重试。
 */
public interface ContentTopicTagService {

    /**
     * 重新读取帖子当前状态并生成一次主题标签。
     * 已处理（包括空数组）或不可见帖子直接跳过。
     *
     * @param contentId 帖子 ID
     */
    void tagContent(Long contentId);

    /**
     * 将审核通过且 tags 为 NULL 的帖子按内容 ID 游标批量登记为 Outbox 事件。
     * 返回本批最后一个内容 ID；没有可入队内容时返回原 afterId。
     *
     * @param afterId 上一批最后一个内容 ID（不含）
     * @param limit 请求批量大小，会被配置上限截断
     * @return 下一次调用应使用的游标
     */
    long enqueueBackfill(long afterId, int limit);
}
