package com.quanta.demo0.answer.service.impl;

import com.quanta.demo0.answer.entity.QuestionAnswer;
import com.quanta.demo0.answer.mapper.QuestionMapper;
import com.quanta.demo0.content.service.ContentQueryService;
import com.quanta.demo0.content.vo.ContentSnapshotVO;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** 回答快照与 RAG 分页契约；ES 全状态重建不复用已审核分页。 */
class AnswerQueryServiceImplSnapshotTest {
    @Test
    void approvedRagPaginationUsesApprovedSqlAndRawParentSnapshot() throws Exception {
        QuestionMapper mapper = mock(QuestionMapper.class);
        ContentQueryService contentService = mock(ContentQueryService.class);
        AnswerQueryServiceImpl service = new AnswerQueryServiceImpl();
        ReflectionTestUtils.setField(service, "questionMapper", mapper);
        ReflectionTestUtils.setField(service, "contentQueryService", contentService);
        when(mapper.selectApprovedAnswersForReindex(100, 100)).thenReturn(List.of(
                QuestionAnswer.builder().answerId(8L).questionId(5L).auditStatus(1).isDeleted(0).content("回答").build()));
        when(contentService.getContentSnapshot(5L)).thenReturn(ContentSnapshotVO.builder()
                .contentId(5L).auditStatus(2).title("问题").content("正文").build());

        var batch = service.getApprovedAnswerRagSnapshots(100, 100);

        assertThat(batch).hasSize(1);
        assertThat(batch.get(0).getQuestionTitle()).isEqualTo("问题");
        verify(mapper).selectApprovedAnswersForReindex(100, 100);
        verify(mapper, never()).selectAllAnswersForReindex(anyInt(), anyInt());
        verify(contentService).getContentSnapshot(5L);
        Configuration configuration = new Configuration();
        String resource = "mapper/answer/QuestionMapper.xml";
        try (var input = getClass().getClassLoader().getResourceAsStream(resource)) {
            new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
        }
        String sql = configuration.getMappedStatement(QuestionMapper.class.getName()
                + ".selectApprovedAnswersForReindex").getBoundSql(Map.of("offset", 100, "limit", 100))
                .getSql().toLowerCase();
        assertThat(sql).contains("is_deleted = 0", "audit_status = 1");
        assertThat(sql.indexOf("audit_status")).isLessThan(sql.indexOf("limit"));
    }
}
