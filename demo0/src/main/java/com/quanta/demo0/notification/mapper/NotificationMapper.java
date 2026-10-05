package com.quanta.demo0.notification.mapper;

import com.github.pagehelper.Page;
import com.quanta.demo0.notification.entity.Notification;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 通知表数据访问接口。
 *
 * 【注解 SQL 与 XML 混用的分工】查询（只读、需要被 PageHelper 拦截改写）用
 * @Select 注解就近可读；写操作（insert / 已读更新）放
 * src/main/resources/mapper/notification/NotificationMapper.xml，
 * 便于使用 useGeneratedKeys 和多条件 UPDATE。
 */
@Mapper
public interface NotificationMapper {
    // 当前用户的分页通知，按创建时间倒序；PageHelper 在外层拼 LIMIT，Page 携带 total
    @Select("select * from tb_notification where recipient_user_id = #{userId} and is_deleted=0 order by create_time desc")
    Page<Notification> pageByUserId(Long userId);

    // 未读数口径：is_read=0 且 is_deleted=0，与列表查询的过滤条件保持一致
    @Select("select count(*) from tb_notification where recipient_user_id = #{userId} and is_read = 0 and is_deleted = 0")
    Integer countUnreadByUserId(Long userId);


    // 已读更新：WHERE 带 recipient_user_id，天然防越权（别人的 id 影响 0 行，服务层据此抛异常）
    int updateIsRead(@Param("id") Long id, @Param("userId") Long userId);

    // 全部已读：只刷 is_read=0 的行，避免无谓刷新已读记录的 update_time
    int markAllAsReadByUserId(@Param("userId") Long userId);

    // 消费链路落库入口：XML 中 useGeneratedKeys 回填自增 id，供推送 VO 使用
    void insert(Notification notification);
}
