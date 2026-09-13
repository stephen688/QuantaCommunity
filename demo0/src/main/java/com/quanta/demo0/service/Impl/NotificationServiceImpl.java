package com.quanta.demo0.service.Impl;

import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.quanta.demo0.entity.BaseContext;
import com.quanta.demo0.entity.Notification;
import com.quanta.demo0.enums.NotificationType;
import com.quanta.demo0.exception.NoFoundException;
import com.quanta.demo0.mapper.NotificationMapper;
import com.quanta.demo0.service.NotificationService;
import com.quanta.demo0.vo.NotificationVO;
import com.quanta.demo0.vo.PageVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 通知读侧服务实现类。
 *
 * 核心职责：
 * 1. 提供通知分页查询、未读数统计、单条/全部已读能力；
 * 2. 将通知实体转换为前端展示对象，统一输出格式；
 * 3. 作为通知推送链路的读模型补充，保证客户端可回溯历史消息。
 *
 * 设计说明：
 * - 写入链路由 MQ/WebSocket 异步驱动，本类聚焦同步查询与状态更新；
 * - 已读更新附带收件人条件，防止越权修改他人通知状态。
 */
@Service
public class NotificationServiceImpl implements NotificationService {

    @Autowired
    private NotificationMapper notificationMapper;

    /**
     * 分页查询通知列表
     * @param page 页码
     * @param pageSize 每页数量
     * @return 分页通知列表
     */
    @Override
    public PageVO<NotificationVO> pageNotifications(Integer page, Integer pageSize) {
        //1.获取当前用户id
        Long userId = BaseContext.getCurrentId();
        //2.查询通知列表（分页）
        PageHelper.startPage(page, pageSize);
        Page<Notification> notifications = notificationMapper.pageByUserId(userId);
        //3.获取总记录数和实例对象
        List<Notification> notificationList = notifications.getResult();
        long total = notifications.getTotal();
        //4.转换为VO
        List<NotificationVO> voList = notificationList.stream()
                .map(this::convertToVO)
                .toList();
        //5.封装返回
        PageVO<NotificationVO> pageVO = new PageVO<>();
        pageVO.setList(voList);
        pageVO.setTotal(total);
        pageVO.setPageNum(page);
        pageVO.setPageSize(pageSize);
        pageVO.setTotalPage((int) ((total + pageSize - 1) / pageSize));
        pageVO.setHasMore(page * pageSize < total);
        return pageVO;

    }

    /**
     * 查询未读通知数量
     * @return
     */
    @Override
    public Integer getUnreadCount() {
        Long userId = BaseContext.getCurrentId();

        return notificationMapper.countUnreadByUserId(userId);
    }

    @Override
    public void markAsRead(Long id) {
        Long userId = BaseContext.getCurrentId();
        // update 条件含 recipient_user_id，仅收件人可标已读
        int rows = notificationMapper.updateIsRead(id, userId);
        if (rows == 0) {
            throw new NoFoundException("通知不存在或无权操作");
        }
    }
    @Override
    public void markAllAsRead() {
        Long userId = BaseContext.getCurrentId();
        notificationMapper.markAllAsReadByUserId(userId);
    }

    /**
     * 将 Notification 实体转换为 NotificationVO
     *
     * @param notification 通知实体
     * @return 通知VO
     */
    private NotificationVO convertToVO(Notification notification) {
        // 查找通知类型描述
        String typeDesc = getTypeDesc(notification.getType());

        return NotificationVO.builder()
                .id(notification.getId())
                .actorUserId(notification.getActorUserId())// 触发者用户ID(系统通知可为空)
                .type(notification.getType())// 通知类型代码
                .typeDesc(typeDesc)// 通知类型描述
                .content(notification.getContent())// 通知摘要内容
                .payload(notification.getPayload())// 扩展字段JSON
                .isRead(notification.getIsRead())
                .createTime(notification.getCreateTime())
                .build();
    }


    /**
     * 根据类型代码获取描述
     * @param typeCode 类型代码
     * @return 类型描述
     */
    private String getTypeDesc(String typeCode) {
        try {
            return NotificationType.valueOf(typeCode).getDesc();
        } catch (IllegalArgumentException e) {
            return "未知通知";
        }
    }
}
