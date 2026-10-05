package com.quanta.demo0.content.service;

/**
 * 内容主题标签服务。
 *
 * 职责：为当前可见帖子异步生成受控主题标签，或将未处理存量帖子按 ID 游标入队；
 * 边界：不修改审核状态，不直接发送 RabbitMQ，外部模型失败由消息消费者重试。
 *
 * ============================================================
 * 【两个方法的调用方与失败语义（接口契约先讲清）】
 * ============================================================
 * tagContent：唯一调用方是 ContentTopicTagConsumer（MQ 消费线程）。
 * 它的**异常就是重试信号**：抛异常 = 消费失败 → 延迟重试队列/死信；
 * 正常 return（包括"已处理过跳过"）= 成功终态 → Inbox 记 SUCCESS。
 * 所以"打不了标"时宁可抛异常，也不要 return 布尔值的静默——语义全靠异常/返回表达。
 *
 * enqueueBackfill：唯一调用方是管理端回填 Controller（HTTP 线程）。
 * 它**只派活不打标**——模型调用慢，绝不能发生在 HTTP 请求线程里；
 * 契约是"游标推进"：返回下一次应使用的 contentId，调用方循环到没有新增为止。
 * （实现与逐行讲解见 ContentTopicTagServiceImpl。）
 */
public interface ContentTopicTagService {

    /**
     * 重新读取帖子当前状态并生成一次主题标签。
     * 已处理（包括空数组）或不可见帖子直接跳过。
     *
     * 【重复消费会怎样】tags 非 NULL 即跳过（NULL=未处理，[]=处理过但无命中），
     * 消息重复投递/实例重复抢占都不会重复消耗模型——数据状态本身就是幂等标记。
     * 【失败语义】内容不可见、模型超时、输出不合法都抛异常 → 消费端按 retryCount
     * 分流重试与死信；写入用条件 UPDATE（tags IS NULL 才生效）兜住并发。
     *
     * @param contentId 帖子 ID
     */
    void tagContent(Long contentId);

    /**
     * 将审核通过且 tags 为 NULL 的帖子按内容 ID 游标批量登记为 Outbox 事件。
     * 返回本批最后一个内容 ID；没有可入队内容时返回原 afterId。
     *
     * 【幂等】确定性 eventId + appendIfAbsent（见 ContentEventProducer.createContentTopicTagEvent）：
     * 同一帖子无论被回填扫到多少次，Outbox 里只会有一条打标事件。
     * 【快速失败】afterId/limit 非法直接抛 IllegalArgumentException——管理端参数错误当场暴露。
     *
     * @param afterId 上一批最后一个内容 ID（不含）
     * @param limit 请求批量大小，会被配置上限截断
     * @return 下一次调用应使用的游标
     */
    long enqueueBackfill(long afterId, int limit);
}
