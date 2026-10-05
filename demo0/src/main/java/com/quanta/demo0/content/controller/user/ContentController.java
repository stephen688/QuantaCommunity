package com.quanta.demo0.content.controller.user;

import com.quanta.demo0.platform.security.annotation.RateLimit;
import com.quanta.demo0.platform.security.constant.RoleConstants;
import com.quanta.demo0.platform.common.result.Result;
import com.quanta.demo0.content.service.ContentQueryService;
import com.quanta.demo0.content.service.ContentCommandService;
import com.quanta.demo0.feed.service.FeedQueryService;
import com.quanta.demo0.feed.dto.RecommendVisitor;
import com.quanta.demo0.feed.vo.RecommendPageVO;
import com.quanta.demo0.platform.security.context.BaseContext;
import com.quanta.demo0.interaction.service.ContentInteractionService;
import com.quanta.demo0.interaction.service.ReportGovernanceService;
import com.quanta.demo0.interaction.vo.CollectResultVO;
import com.quanta.demo0.content.vo.ContentVO;
import com.quanta.demo0.interaction.vo.LikeResultVO;
import com.quanta.demo0.platform.web.idempotency.enums.SubmissionScene;
import com.quanta.demo0.platform.web.idempotency.service.SubmissionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import com.quanta.demo0.content.dto.ContentDTO;
import com.quanta.demo0.feed.dto.RecommendQueryDTO;
import com.quanta.demo0.interaction.dto.CollectStateDTO;
import com.quanta.demo0.interaction.dto.ContentReportDTO;
import com.quanta.demo0.interaction.dto.LikeStateDTO;

/**
 * 内容接口
 * （用户侧）。
 *
 * ============================================================
 * 【Controller 的职责边界：薄到只剩"翻译"】
 * ============================================================
 * 这个类里没有任何业务判断 —— 每个方法只做三件事：
 * 接参数（@PathVariable / @RequestBody）→ 调一个域 Service → 包 Result 返回。
 * 为什么要这么薄：Controller 层没有事务、没有回滚语义，
 * 一旦在这里写业务（哪怕一个 if），这段逻辑就游离在事务边界之外，
 * 且没法被别的入口（Bot、Admin、MQ Consumer）复用。
 * **业务只写在 Service，Controller 是 HTTP 与领域之间的防腐层。**
 *
 * 注意依赖注入的形状：本类同时注入了 feed / interaction 两个域的 Service
 * —— 这是允许的（跨域调公开 Service），但注意点赞/收藏虽然路由在
 * /content/like，实际调的是 ContentInteractionService ——
 * **URL 归属 ≠ 域归属**：URL 按用户心智组织（帖子页面上的按钮），
 * 代码按业务域组织，两者不必一致。
 *
 * ============================================================
 * 【身份从哪来：token，而不是参数】
 * ============================================================
 * 所有写接口（发布/删除/点赞/收藏/举报）的入参里都没有 userId ——
 * 操作者身份一律由 JWT 过滤器解析后放进 BaseContext，Service 再取。
 * 如果允许客户端在 body 里传 userId，改个数字就能替别人发帖/删帖（水平越权）。
 * **能从凭证推导的状态，绝不让客户端声明。**
 */
@RestController
@Slf4j
@RequestMapping("/content")
public class ContentController {

    @Autowired
    private ContentQueryService contentQueryService;
    @Autowired
    private ContentCommandService contentCommandService;
    @Autowired
    private FeedQueryService feedQueryService;
    @Autowired
    private ContentInteractionService contentInteractionService;
    @Autowired
    private ReportGovernanceService reportGovernanceService;
    @Autowired
    private SubmissionService submissionService;
    /**
     * 发布内容
     *
     * 【两层防线，各管一头】
     * @RateLimit：频控 —— 60 秒最多 5 次，防的是"刷帖"（恶意高频），
     *   Lua 原子窗口实现，Redis 挂了按场景配置放行或拒绝（fail-open/closed 分级）。
     * @PreAuthorize(VERIFIED_USER)：身份门槛 —— 只有通过身份认证考试的用户能发帖，
     *   防的是"注册即发广告"（低门槛滥用）。
     * 一个管"多快"，一个管"谁能"，**限流不能替代权限，权限不能替代限流**。
     *
     * 【注意 Controller 里没有 @Valid】
     * ContentDTO 上没挂校验注解，参数校验在 CommandService 里手写 ——
     * 不理想（Bean Validation 是标准做法），但校验放在 Service 里保证了
     * 其他入口（Bot 发帖、管理端）不会绕过规则。两害取其轻：**校验离写路径最近**。
     */

    @RateLimit(
            scene = "content-publish",
            limit = 5,
            windowSeconds = 60
    )
    @PreAuthorize("hasRole('" + RoleConstants.VERIFIED_USER + "')")
    @PostMapping("/publish")
    public Result<ContentVO> publish(
            @RequestBody ContentDTO contentDTO,
            @RequestHeader(value = "Idempotency-Key", required = false) String submissionToken) {
        log.info("发布内容：{}", contentDTO);
        ContentVO contentVO = submissionService.execute(
                SubmissionScene.CONTENT_PUBLISH,
                submissionToken,
                contentDTO,
                ContentVO.class,
                () -> contentCommandService.publish(contentDTO));
        return Result.success(contentVO);
    }
    /**
     * 滑动分页查询推荐内容
     * （游标分页，非页码分页）。
     *
     * RecommendPageVO 保留旧分数分页字段，并增加推荐会话与页游标。
     * 新协议固定本轮候选上界、页面 ID 和元数据，重试同一页不会消耗下一页；
     * 热度及旧客户端继续使用已有 ZSET 分数分页。
     *
     * 【注意这个方法没有 @PreAuthorize】
     * 未登录用户也能刷 Feed。新推荐协议按热度兜底并插入近期探索，
     * 登录后使用服务端认证身份排序；旧客户端保留原协议——认证是可选的，
     * 用 isAnonymous 之类的注解反而把"可选登录"写死了。
     */

    @GetMapping("/recommend")
    public Result<RecommendPageVO> recommend(
            @ModelAttribute RecommendQueryDTO recommendQueryDTO,
            @RequestHeader(value = "X-Guest-Id", required = false) String guestId) {
        boolean discoveryRequest = recommendQueryDTO.getFeedSessionId() != null
                && !"hot".equals(recommendQueryDTO.getScene());
        RecommendVisitor visitor = discoveryRequest
                ? RecommendVisitor.from(BaseContext.getCurrentId(), guestId)
                : new RecommendVisitor(BaseContext.getCurrentId(), null);
        log.info("查询推荐内容，scene={}，type={}", recommendQueryDTO.getScene(), recommendQueryDTO.getContentType());
        return Result.success(feedQueryService.recommend(recommendQueryDTO, visitor));
    }

    /**
     * 根据contentId查询内容详情
     * 【面试点】"帖子不存在"和"无权查看"统一返回业务码"内容不存在"——
     * 不泄露"这个帖子存在但被删/未过审"的区分（防探测，同 404 优于 403 的思路）。
     */

    @PreAuthorize("isAuthenticated()")
    @GetMapping("/detail/{contentId}")
    public Result<ContentVO> getContentDetail(@PathVariable Long contentId) {
        log.info("查询内容详情：{}", contentId);
        ContentVO contentVO = contentQueryService.getContentDetail(contentId);
        return Result.success(contentVO);
    }
    /**
     * 删除内容
     * （仅作者本人）。
     * 【面试点】权限校验在 Service 里做（对比发布用户的 userId），
     * 而不是在 Controller 拦 —— 因为 Bot/管理端也会触发删除，规则必须只有一份。
     */
    @PreAuthorize("isAuthenticated()")
    @DeleteMapping("/delete/{contentId}")
    public Result<Void> deleteContent(@PathVariable Long contentId) {
        log.info("删除内容：{}", contentId);
        contentCommandService.deleteContent(contentId);
        return Result.success();
    }


    /**
     * 根据contentId点赞内容
     *
     * 【幂等的关键在入参形状：传"目标状态"，不传"动作"】
     * body 里是 liked=true/false（Set 语义），不是 toggle() ——
     * 网络重试把同一请求发两遍，结果和发一遍相同（Service 里按 insert 影响行数判断，
     * 重复点赞不再加计数，见 ContentInteractionServiceImpl.likeContent）。
     * 如果入参是"切换"动作，重试就会 +1 再 -1 抖动 —— **写接口尽量设计成幂等的 Set，而不是增量的 Toggle**。
     */

    @PreAuthorize("isAuthenticated()")
    @PostMapping("/like/{contentId}")
    public Result<LikeResultVO> likeContent(@PathVariable Long contentId,
                                            @Valid @RequestBody LikeStateDTO stateDTO) {
        log.info("点赞内容：{}，点赞状态：{}", contentId, stateDTO.getLiked());
        LikeResultVO likeResultVO = contentInteractionService.likeContent(contentId, stateDTO.getLiked());
        return Result.success(likeResultVO);
    }
    /**
     * 收藏或取消收藏内容
     * 与点赞同构：传目标状态 collected，重复提交安全（幂等），不再展开。
     */
    @PreAuthorize("isAuthenticated()")
    @PostMapping("/collect/{contentId}")
    public Result<CollectResultVO> collect(@PathVariable Long contentId,
                                            @Valid @RequestBody CollectStateDTO stateDTO) {
        log.info(" 收藏/取消收藏内容,内容ID:{}，收藏状态:{}", contentId, stateDTO.getCollected());
        CollectResultVO collectResultVO = contentInteractionService.collect(contentId, stateDTO.getCollected());
        return Result.success(collectResultVO);
    }
    /**
     * 举报帖子
     * 【规则】
     * - 同一用户对同一帖子只能举报一次
     * - 举报类型：1-垃圾广告 2-人身攻击 3-违规内容 4-虚假信息 5-其他
     *
     * @param contentReportDTO 举报数据
     * @return 成功
     */
    @RateLimit(
            scene = "content-report",
            limit = 5,
            windowSeconds = 60
    )
    @PreAuthorize("isAuthenticated()")
    @PostMapping("/report")
    public Result reportContent(@RequestBody ContentReportDTO contentReportDTO) {
        log.info("举报帖子: {}", contentReportDTO);
        reportGovernanceService.reportContent(contentReportDTO);
        return Result.success();
    }


}
