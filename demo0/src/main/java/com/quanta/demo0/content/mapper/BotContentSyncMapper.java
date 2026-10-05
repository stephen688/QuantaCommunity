package com.quanta.demo0.content.mapper;

import com.quanta.demo0.content.vo.BotSyncDocVO;
import com.quanta.demo0.content.entity.BotPolicyDoc;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/** 三源内容同步与政策文档数据访问（C-3）。 */

/**
 * "三源"= 帖子（tb_content）+ 回答（tb_question_answer）+ 政策文档（tb_bot_policy_doc）。
 * selectSyncBatch 用 UNION ALL 把三者拼成统一的同步文档流：docId 前缀 content:/answer:/
 * （政策文档用原样 doc_id），docKind=POST/ANSWER/POLICY，按 update_time 水位线
 * 增量喂给 QuantaBot 的知识库（见 BotContentSyncServiceImpl）。
 *
 * ============================================================
 * 【同步条件为什么写成 (audit_status = 1 OR is_deleted = 1)？】
 * ============================================================
 * 同步的目的不只是"给他看"，还包括"告诉他收走"：is_deleted=1 的行也在结果集里，
 * status 被标成 'deleted'，QuantaBot 收到后从自己的知识库移除该文档 ——
 * **删除必须作为事件传播，否则下游永远留着幽灵知识**。这也决定了拉取口径：
 * WHERE update_time >= #{since} 用"大于等于"，宁重不漏 —— 少推一条删失就漏删，
 * 重复推一条由下游按 docId 覆盖，代价为零（见 BotContentSyncServiceImpl 类注释）。
 */
@Mapper
public interface BotContentSyncMapper {

    /**
     * 按水位线拉取一批同步文档（SQL 在 BotContentSyncMapper.xml）：
     * 三源 UNION ALL 后按 updateTime, docId 排序、LIMIT #{offset}, #{limit} 分页 ——
     * 排序键稳定，翻页期间数据变动也不会乱序；deleted 状态行原样带出，由消费方移除。
     * 【注意】offset 分页在水位线推进期间会重复读到边界行，靠"宁重不漏"语义消化。
     */
    List<BotSyncDocVO> selectSyncBatch(
            @Param("since") LocalDateTime since,
            @Param("offset") int offset,
            @Param("limit") int limit);

    /**
     * 统计三源自水位线以来的变更总数，供分页计算 hasMore；
     * 三个子查询相加，过滤条件与 selectSyncBatch 保持同一口径（宁重不漏）。
     */
    @Select("""
            SELECT
              (SELECT COUNT(*) FROM tb_content c WHERE (c.audit_status = 1 OR c.is_deleted = 1) AND c.update_time >= #{since}) +
              (SELECT COUNT(*) FROM tb_question_answer a WHERE (a.audit_status = 1 OR a.is_deleted = 1) AND a.update_time >= #{since}) +
              (SELECT COUNT(*) FROM tb_bot_policy_doc p WHERE p.update_time >= #{since})
            """)
    long countSync(@Param("since") LocalDateTime since);

    /**
     * 按业务键查政策文档（limit 1 兜底异常重复的 docId），
     * 是 upsert 的"查重"一步：查到走更新，查不到走插入。
     */
    @Select("select * from tb_bot_policy_doc where doc_id = #{docId} limit 1")
    BotPolicyDoc selectByDocId(@Param("docId") String docId);

    /**
     * 新增政策文档：只写业务三列（docId/title/content），软删与时间列交给表侧默认值。
     */
    @Insert("""
            insert into tb_bot_policy_doc (doc_id, title, content)
            values (#{docId}, #{title}, #{content})
            """)
    int insertPolicyDoc(BotPolicyDoc document);

    /**
     * 按 docId 覆盖标题与正文，并顺带 is_deleted = 0 —— 已软删的 docId 重新 upsert
     * 即"复活"；update_time = now() 保证本次变更会被下一次水位线同步带出。
     */
    @Update("""
            update tb_bot_policy_doc
            set title = #{title}, content = #{content}, is_deleted = 0, update_time = now()
            where doc_id = #{docId}
            """)
    int updatePolicyDocByDocId(BotPolicyDoc document);

    /**
     * 软删：WHERE is_deleted = 0 让重复软删影响 0 行（幂等），
     * update_time = now() 让删除被下一次水位线同步传播出去。
     * @return 实际更新行数
     */
    @Update("update tb_bot_policy_doc set is_deleted = 1, update_time = now() where doc_id = #{docId} and is_deleted = 0")
    int softDeletePolicyDocByDocId(@Param("docId") String docId);
}
