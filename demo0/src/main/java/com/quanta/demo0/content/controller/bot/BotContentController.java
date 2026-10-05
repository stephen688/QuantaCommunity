package com.quanta.demo0.content.controller.bot;

import com.quanta.demo0.platform.security.annotation.RateLimit;
import com.quanta.demo0.platform.security.constant.RoleConstants;
import com.quanta.demo0.content.vo.BotSyncPageVO;
import com.quanta.demo0.content.dto.BotPolicyDocDTO;
import com.quanta.demo0.platform.common.result.Result;
import com.quanta.demo0.content.service.BotContentSyncService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** bot 内容同步与政策文档接口（C-3）。 */
/**
 * 职责补充：QuantaBot 的内容域入口 —— 只读增量同步 + 运营侧政策文档维护。
 *
 * ============================================================
 * 【为什么 Bot 用"拉 + 水位线"，而不是服务端推送】
 * ============================================================
 * QuantaBot 是独立部署的外部进程（自己的模型、自己的知识库），不属于本单体的事件域 ——
 * MQ 事件推不到它，也不该推：让服务端感知 Bot 的死活/重试/限速，耦合就反了。
 * 所以契约倒过来：**服务端只暴露一个只读增量接口，Bot 拿着自己的水位线（since）主动拉**，
 * 节奏、重试、失败恢复全部由 Bot 自治 —— 跨进程边界用拉，进程内才用推（事件）。
 *
 * 【VO 就是契约：字段名冻结】
 * BotSyncDocVO 的字段名直接决定 JSON 形状，Bot 端按它反序列化 ——
 * 这里不能随意改字段/删字段，宁可冗余也不能破坏已发布的 Bot 版本（见 BotSyncDocVO 的说明）。
 * 内部表结构怎么变是 content 包自己的事，**契约层隔开了"内部演进"和"外部稳定"**。
 *
 * 【三层身份，三个入口】
 * 用户侧 /content、管理端 /admin/content、这里 /bot —— 同一个内容域，
 * 按调用方分 Controller：权限模型不同（hasRole('BOT')）、契约形状不同（SyncDoc）、
 * 频控策略不同（bot-read 每分钟 120 次）。
 */
@RestController
@RequestMapping("/bot")
@RequiredArgsConstructor
public class BotContentController {

    private final BotContentSyncService botContentSyncService;


    /**
     * 增量同步内容（帖子/回答/政策文档三源合一）。
     *
     * 【since 是水位线，不是游标】Bot 传"上次同步到的 update_time"，
     * 服务端返回该时刻之后变过的所有文档（含软删的，status=deleted）。
     * 注意 SQL 用 update_time &gt;= since（含等号）—— **宁重不漏**：
     * 边界上的文档可能重复下发，但 Bot 按 docId 做 upsert，重复消化无害；
     * 漏一条就是知识库静默缺内容，代价不对称。
     *
     * 【这里又是页码分页，和 Feed 的游标分页矛盾吗？】不矛盾（对比见 ContentController.recommend）：
     * 消费方是程序不是人，只按 hasMore 翻页；小增量集 + 宁重不漏语义下，
     * 页码偏移的重复无害 —— 简单性赢了。
     *
     * 【failClosed = false 的取舍】限流组件依赖 Redis；Bot 的读请求被拒了也只是
     * 下一轮再拉（数据还在），所以 Redis 故障时选择放行而不是拒绝 ——
     * **读接口、可重试、调用方是机器 → fail-open**。对比发布接口的默认 fail-closed。
     *
     * @param since 水位线，支持 yyyy-MM-dd 或 yyyy-MM-dd HH:mm:ss（解析在 Service）
     */
    @GetMapping("/content/sync")
    @PreAuthorize("hasRole('BOT')")
    @RateLimit(scene = "bot-read", limit = 120, windowSeconds = 60, failClosed = false)
    public Result<BotSyncPageVO> sync(
            @RequestParam String since,
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "50") Integer pageSize) {
        return Result.success(botContentSyncService.getSync(since, pageNum, pageSize));
    }

    /**
     * 登记政策文档（upsert：新增或更新/复活软删文档）。
     *
     * 【为什么权限是运营而不是 Bot】政策文档是"运营想喂给 Bot 的官方口径"，
     * 属于人工决策的内容 —— Bot 自己只有读权（sync 接口），写权在 OPERATIONS_ADMIN。
     * **同一个 URL 前缀 /bot，写操作的身份是人**。
     *
     * 【docId 由调用方指定，重复提交 = 覆盖】docId 是幂等键，同一 docId 再调
     * 就是更新内容（顺带把软删的文档复活，is_deleted 置回 0）——
     * 幂等写让"同步脚本重跑"不需要任何去重逻辑。参数校验在
     * BotContentSyncServiceImpl.validatePolicyDoc（与本项目其他 DTO 一致：校验离写路径最近）。
     */
    @PostMapping("/knowledge/policy-docs")
    @PreAuthorize("hasRole('" + RoleConstants.OPERATIONS_ADMIN + "')")
    @RateLimit(scene = "bot-policy-write", limit = 20, windowSeconds = 60)
    public Result<Void> upsertPolicyDoc(@RequestBody BotPolicyDocDTO dto) {
        botContentSyncService.upsertPolicyDoc(dto);
        return Result.success();
    }

    /**
     * 删除政策文档（软删墓碑）。
     *
     * 【删除如何传播给 Bot？—— 不推送，走同步通道】
     * 这里只把 is_deleted 置 1 并刷新 update_time —— update_time 变了，
     * 这条"墓碑"就会出现在 Bot 的下一次 sync 结果里（status=deleted），
     * Bot 据此删掉本地知识。**删除也是一种"变更"，让变更只有一条传播路径**，
     * 不需要为删除单开推送/事件。
     */
    @DeleteMapping("/knowledge/policy-docs/{docId}")
    @PreAuthorize("hasRole('" + RoleConstants.OPERATIONS_ADMIN + "')")
    @RateLimit(scene = "bot-policy-write", limit = 20, windowSeconds = 60)
    public Result<Void> deletePolicyDoc(@PathVariable String docId) {
        botContentSyncService.softDeletePolicyDoc(docId);
        return Result.success();
    }
}
