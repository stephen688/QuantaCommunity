package com.quanta.demo0.platform.web.idempotency;

import com.quanta.demo0.platform.web.idempotency.controller.SubmissionController;
import com.quanta.demo0.platform.web.idempotency.enums.SubmissionScene;
import com.quanta.demo0.platform.web.idempotency.enums.SubmissionStatus;
import com.quanta.demo0.platform.web.idempotency.service.SubmissionService;
import com.quanta.demo0.platform.web.idempotency.vo.SubmissionStatusVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

/** 状态查询 HTTP 契约的最小测试。 */
@ExtendWith(MockitoExtension.class)
class SubmissionHttpTests {

    @Mock
    private SubmissionService submissionService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = standaloneSetup(new SubmissionController(submissionService))
                .setControllerAdvice(new com.quanta.demo0.platform.web.handler.GlobalExceptionHandler())
                .build();
    }

    @Test
    void statusQueryUsesHeaderTokenAndReturnsSubmissionStatus() throws Exception {
        when(submissionService.query(SubmissionScene.COMMENT_SEND,
                "123e4567-e89b-42d3-a456-426614174000"))
                .thenReturn(SubmissionStatusVO.builder()
                        .scene(SubmissionScene.COMMENT_SEND)
                        .status(SubmissionStatus.UNCONFIRMED)
                        .build());

        mockMvc.perform(get("/submission/status")
                        .param("scene", SubmissionScene.COMMENT_SEND)
                        .header("Idempotency-Key", "123e4567-e89b-42d3-a456-426614174000")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.status").value("UNCONFIRMED"));
    }
}
