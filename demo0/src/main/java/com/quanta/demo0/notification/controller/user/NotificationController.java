package com.quanta.demo0.notification.controller.user;
import com.quanta.demo0.platform.common.result.Result;
import com.quanta.demo0.notification.service.NotificationService;
import com.quanta.demo0.notification.vo.NotificationVO;
import com.quanta.demo0.platform.common.result.PageVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * 用户端 - 通知控制器
 *
 * 通知域的 HTTP 读侧入口：列表、未读数、已读标记。
 * 通知的"产生"不在这里——那走 MQ 消费链路（NotificationConsumer），
 * 前端到本控制器只能拉取和确认，两侧通过 tb_notification 解耦。
 *
 * 【越权防线收在服务层】四个接口都不接 userId 参数（登录人由
 * BaseContext 提供），且全部 @PreAuthorize("isAuthenticated()") 挡住匿名请求。
 */
@RestController
@RequestMapping("/notification")
@Slf4j
public class NotificationController {

    @Autowired
    private NotificationService notificationService;

    /**
     * 分页查询通知列表
     * @param page 页码
     * @param pageSize 每页数量
     * @return 分页通知列表
     */
    @PreAuthorize("isAuthenticated()")
    @GetMapping("/list")
    public Result<PageVO<NotificationVO>> listNotifications(
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "10") Integer pageSize) {

        log.info("分页查询通知列表: page={}, pageSize={}", page, pageSize);

        // 调用 Service 层方法（下一步创建）
        PageVO<NotificationVO> pageVO = notificationService.pageNotifications(page, pageSize);

        return Result.success(pageVO);
    }



    /**
     * 查询未读通知数量
     * @return 未读数量
     *
     * 【红点数据源】实时 count(tb_notification)，无缓存无计数器，
     * 与列表页的已读状态强一致；配合 WebSocket 推送即可实现"弹提醒 + 红点"。
     */
    @PreAuthorize("isAuthenticated()")
    @GetMapping("/unreadCount")
    public Result<Integer> getUnreadCount() {
        log.info("查询未读通知数量");

        // 调用 Service 层方法
        Integer unreadCount = notificationService.getUnreadCount();

        return Result.success(unreadCount);
    }



    /**
     * 标记单条通知为已读
     * @param id 通知ID
     * @return 操作结果
     *
     * 【id 属于谁，SQL 说了算】更新条件带当前登录人（见 NotificationMapper.xml），
     * 拿别人的通知 id 调用只会影响 0 行，服务层抛 NoFoundException。
     */
    @PreAuthorize("isAuthenticated()")
    @PutMapping("/read/{id}")
    public Result markAsRead(@PathVariable Long id) {
        log.info("标记通知为已读: id={}", id);

        notificationService.markAsRead(id);

        return Result.success();
    }

    /**
     * 标记当前用户全部通知为已读
     *
     * 一条 UPDATE 刷完（只更新未读行），无分页无上限，幂等可重复调用。
     */
    @PreAuthorize("isAuthenticated()")
    @PutMapping("/readAll")
    public Result markAllAsRead() {
        log.info("标记全部通知为已读");
        notificationService.markAllAsRead();
        return Result.success();
    }
}
