package com.quanta.demo0.notification.service.impl;

import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.quanta.demo0.platform.security.context.BaseContext;
import com.quanta.demo0.notification.entity.Notification;
import com.quanta.demo0.notification.enums.NotificationType;
import com.quanta.demo0.platform.common.exception.NoFoundException;
import com.quanta.demo0.notification.mapper.NotificationMapper;
import com.quanta.demo0.notification.service.NotificationService;
import com.quanta.demo0.notification.vo.NotificationVO;
import com.quanta.demo0.platform.common.result.PageVO;
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
 *
 * ============================================================
 * 【未读数为什么直接 count 表，而不维护计数器/缓存？】
 * ============================================================
 * getUnreadCount 就是一条 count(*)（NotificationMapper.countUnreadByUserId，
 * 口径 is_read=0 且 is_deleted=0）。当前实现每次都实时聚合：**读到的一定是
 * 准数**，不存在"已读后红点没消"的一致性问题；代价是通知量大时聚合变慢，
 * 届时再引入计数器要处理"落库与已读并发"的加减账，复杂度高一个量级——
 * 读多写少且数据量可控的阶段，直查是更稳的选择。
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
        // 身份取自登录态（BaseContext），接口不接 userId 参数，杜绝越权查他人通知
        Long userId = BaseContext.getCurrentId();
        //2.查询通知列表（分页）
        // PageHelper.startPage 用 ThreadLocal 拦截紧随其后的第一条 SQL 拼 LIMIT，
        // 返回的 Page 是 ArrayList 子类，额外携带 total，取总数不必再 count 一次
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
        // （NotificationMapper.xml 的 updateIsRead：WHERE id AND recipient_user_id AND is_deleted=0）
        int rows = notificationMapper.updateIsRead(id, userId);
        // 影响 0 行 = 通知不存在/已删除/不是自己的，统一按"无权"拒绝，防止越权探测
        if (rows == 0) {
            throw new NoFoundException("通知不存在或无权操作");
        }
    }
    @Override
    public void markAllAsRead() {
        Long userId = BaseContext.getCurrentId();
        // XML 里带 is_read=0 条件：只刷未读行，已读行的 update_time 不被无谓刷新
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
     *
     * 【valueOf 抛异常兜底】枚举里没有的 code（脏数据/历史遗留类型）会抛
     * IllegalArgumentException，捕获后降级为"未知通知"，列表页不会因一条
     * 坏数据整页报错；对比消费者侧 getByCode 返回 null 的防御方式。
     */
    private String getTypeDesc(String typeCode) {
        try {
            return NotificationType.valueOf(typeCode).getDesc();
        } catch (IllegalArgumentException e) {
            return "未知通知";
        }
    }
}
