package com.quanta.demo0.controller.user;
import com.quanta.demo0.result.Result;
import com.quanta.demo0.service.NotificationService;
import com.quanta.demo0.vo.NotificationVO;
import com.quanta.demo0.vo.PageVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * 用户端 - 通知控制器
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
     */
    @PreAuthorize("isAuthenticated()")
    @PutMapping("/readAll")
    public Result markAllAsRead() {
        log.info("标记全部通知为已读");
        notificationService.markAllAsRead();
        return Result.success();
    }
}
