package com.quanta.demo0.interaction.mapper;

import com.github.pagehelper.Page;
import com.quanta.demo0.interaction.dto.CommentReportQueryDTO;
import com.quanta.demo0.interaction.entity.CommentReport;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 互动域评论举报持久化：管理查询与处理不访问评论域私有模型。 */
@Mapper
public interface CommentReportMapper {
    Page<CommentReport> pageReport(@Param("query") CommentReportQueryDTO query);

    @Select("select * from tb_comment_report where id=#{reportId} and is_deleted = 0")
    CommentReport getReportById(@Param("reportId") Long reportId);

    void updateReport(CommentReport updateReport);

}
