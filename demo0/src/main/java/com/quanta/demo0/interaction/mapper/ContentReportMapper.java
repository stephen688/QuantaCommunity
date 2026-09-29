package com.quanta.demo0.interaction.mapper;

import com.github.pagehelper.Page;
import com.quanta.demo0.interaction.dto.ContentReportQueryDTO;
import com.quanta.demo0.interaction.entity.ContentReport;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 互动域举报治理持久化：保留原内容管理页查询和状态更新 SQL。 */
@Mapper
public interface ContentReportMapper {
    Page<ContentReport> pageReport(@Param("query") ContentReportQueryDTO query);

    @Select("select * from tb_content_report where id=#{id} and is_deleted = 0")
    ContentReport getReportById(Long reportId);

    void updateReport(ContentReport updateReport);
}
