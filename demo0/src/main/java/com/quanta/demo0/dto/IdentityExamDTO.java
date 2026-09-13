package com.quanta.demo0.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.LocalDate;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class IdentityExamDTO {
    /**
     * 页码
     */
    @Builder.Default
    private Integer pageNum=1 ;

    /**
     * 每页数量*
     *
     */
    @Builder.Default
   private Integer pageSize=10 ;

    /**
     * 审核状态 (可选)
     */
    private Integer auditStatus;

/**
 * 开始时间
 */
    private LocalDate startTime;

    /**
     * 结束时间
     */
    private LocalDate endTime;
}
