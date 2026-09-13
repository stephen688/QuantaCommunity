package com.quanta.demo0.service;

import com.quanta.demo0.vo.NotificationVO;
import com.quanta.demo0.vo.PageVO;

public interface NotificationService {
    PageVO<NotificationVO> pageNotifications(Integer page, Integer pageSize);

    Integer getUnreadCount();

    void markAsRead(Long id);

    void markAllAsRead();
}
