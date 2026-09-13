package com.quanta.demo0.mapper;

import com.github.pagehelper.Page;
import com.quanta.demo0.entity.Notification;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface NotificationMapper {
    @Select("select * from tb_notification where recipient_user_id = #{userId} and is_deleted=0 order by create_time desc")
    Page<Notification> pageByUserId(Long userId);

    @Select("select count(*) from tb_notification where recipient_user_id = #{userId} and is_read = 0 and is_deleted = 0")
    Integer countUnreadByUserId(Long userId);


    int updateIsRead(@Param("id") Long id, @Param("userId") Long userId);

    int markAllAsReadByUserId(@Param("userId") Long userId);

    void insert(Notification notification);
}
