package com.quanta.demo0.controller.admin;

import com.quanta.demo0.constant.PermissionConstants;
import com.quanta.demo0.platform.common.result.Result;
import com.quanta.demo0.service.ContentTopicTagService;
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
 */
@RestController
@RequestMapping("/admin/content/topic-tags")
@RequiredArgsConstructor
public class ContentTopicTagBackfillController {

    private final ContentTopicTagService contentTopicTagService;

    /**
     * 登记一批未处理存量帖子。
     *
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
