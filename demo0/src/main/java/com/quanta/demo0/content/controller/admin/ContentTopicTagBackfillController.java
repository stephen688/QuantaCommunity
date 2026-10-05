package com.quanta.demo0.content.controller.admin;

import com.quanta.demo0.platform.security.constant.PermissionConstants;
import com.quanta.demo0.platform.common.result.Result;
import com.quanta.demo0.content.service.ContentTopicTagService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 内容主题标签存量回填入口。
 *
 * 职责：按内容 ID 游标登记有限数量的标签 Outbox 事件；
 * 边界：不在 HTTP 请求中调用模型，不执行全量回填，调用方通过返回游标续跑。
 *
 * ============================================================
 * 【为什么是"HTTP 触发 + 游标续跑"，而不是一键全量或启动任务】
 * ============================================================
 * 存量回填 = 给历史帖子补打 LLM 标签，真正的打标在 MQ 消费端异步做
 * （见 ContentTopicTagServiceImpl），这个接口只负责"往 Outbox 里登记任务"。
 * 即便如此，一次登记 10 万条也有两个坑：单事务写 10 万行 Outbox 拖垮发布事务；
 * 失败后不知道跑到哪了，只能从头再来（好消息是确定性 eventId 会去重，坏消息是白扫一遍表）。
 * 所以这里把进度状态交给调用方持有：**每次只登记 limit 条，返回"处理到的最大 contentId"当游标**，
 * 运维脚本/定时任务拿游标反复调用即可 —— 可中断、可续跑、可限速。
 * 与 ContentEventProducer 的 appendIfAbsent + 确定性 eventId 配合，
 * **同一批重复调用也不会产生重复打标任务（生产端幂等）**。
 */
@RestController
@RequestMapping("/admin/content/topic-tags")
@RequiredArgsConstructor
public class ContentTopicTagBackfillController {

    private final ContentTopicTagService contentTopicTagService;

    /**
     * 登记一批未处理存量帖子。
     *
     * 【游标语义】afterId = 上一批返回值（"处理到的最大 contentId"），
     * SQL 按 content_id > afterId 扫"已审核通过 + tags IS NULL"的帖子 ——
     * tags=NULL 表示未处理过，终态语义见 ContentTopicTagServiceImpl 的说明。
     *
     * 【入参没有"总数"参数】回填不设"跑完 N 条"的目标，只以游标推进 ——
     * 全量范围由 SQL 条件（审核通过且无标签）隐式定义。
     *
     * 【返回值怎么用】返回 afterId 本身 = 本批没有登记任何任务
     * （没扫到数据 / 功能开关关闭），调用方以此判断收尾；
     * 正常情况返回本批最大 contentId，作为下一轮的 afterId。
     *
     * @param afterId 起始游标（不含），首次传 0
     * @param limit 本批条数，超过配置上限会被收紧（见 ContentTopicProperties.maxBackfillBatchSize）
     * @return 下一次请求使用的 afterId
     */
    @PostMapping("/backfill")
    @PreAuthorize("hasAuthority('" + PermissionConstants.CONTENT_AUDIT + "')")
    public Result<Long> enqueueBackfill(
            @RequestParam(defaultValue = "0") long afterId,
            @RequestParam(defaultValue = "10") int limit) {
        return Result.success(contentTopicTagService.enqueueBackfill(afterId, limit));
    }
}
