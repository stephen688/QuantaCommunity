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
@Mapper
public interface BotContentSyncMapper {

    List<BotSyncDocVO> selectSyncBatch(
            @Param("since") LocalDateTime since,
            @Param("offset") int offset,
            @Param("limit") int limit);

    @Select("""
            SELECT
              (SELECT COUNT(*) FROM tb_content c WHERE (c.audit_status = 1 OR c.is_deleted = 1) AND c.update_time >= #{since}) +
              (SELECT COUNT(*) FROM tb_question_answer a WHERE (a.audit_status = 1 OR a.is_deleted = 1) AND a.update_time >= #{since}) +
              (SELECT COUNT(*) FROM tb_bot_policy_doc p WHERE p.update_time >= #{since})
            """)
    long countSync(@Param("since") LocalDateTime since);

    @Select("select * from tb_bot_policy_doc where doc_id = #{docId} limit 1")
    BotPolicyDoc selectByDocId(@Param("docId") String docId);

    @Insert("""
            insert into tb_bot_policy_doc (doc_id, title, content)
            values (#{docId}, #{title}, #{content})
            """)
    int insertPolicyDoc(BotPolicyDoc document);

    @Update("""
            update tb_bot_policy_doc
            set title = #{title}, content = #{content}, is_deleted = 0, update_time = now()
            where doc_id = #{docId}
            """)
    int updatePolicyDocByDocId(BotPolicyDoc document);

    @Update("update tb_bot_policy_doc set is_deleted = 1, update_time = now() where doc_id = #{docId} and is_deleted = 0")
    int softDeletePolicyDocByDocId(@Param("docId") String docId);
}
