package com.quanta.demo0.notification.service;

import com.quanta.demo0.notification.vo.NotificationVO;
import com.quanta.demo0.platform.common.result.PageVO;

/**
 * 通知读侧服务：当前登录用户的通知列表、未读数、已读标记。
 *
 * 【身份一律取登录态】所有方法都不接收 userId 参数，实现类从
 * BaseContext 取当前用户——查询和改动的边界天然就是"自己"，
 * 从接口签名上杜绝了越权查看/修改他人通知的可能。
 * 通知的写入不在这个接口上，走 MQ 消费链路（见 NotificationConsumeService）。
 */
public interface NotificationService {
    PageVO<NotificationVO> pageNotifications(Integer page, Integer pageSize);

    Integer getUnreadCount();

    void markAsRead(Long id);

    void markAllAsRead();
}
