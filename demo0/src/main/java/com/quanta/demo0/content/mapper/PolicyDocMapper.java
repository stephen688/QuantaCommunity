package com.quanta.demo0.content.mapper;

import com.github.pagehelper.Page;
import com.quanta.demo0.content.dto.PolicyDocAdminQueryDTO;
import com.quanta.demo0.content.vo.PolicyDocAdminVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 政策源文档管理查询数据访问层。
 *
 * <p>查询使用专用 Mapper，避免把管理筛选条件混进 Bot 三源同步 SQL；写入仍统一经
 * {@code BotContentSyncService}，保证 upsert/软删/水位线语义只有一个事实源。</p>
 */
@Mapper
public interface PolicyDocMapper {

    /** 按关键词和源文档墓碑状态分页查询。 */
    Page<PolicyDocAdminVO> pageAdmin(@Param("query") PolicyDocAdminQueryDTO query);

    /** 查询单个源文档，包含已软删墓碑。 */
    PolicyDocAdminVO selectAdminDetail(@Param("docId") String docId);
}
