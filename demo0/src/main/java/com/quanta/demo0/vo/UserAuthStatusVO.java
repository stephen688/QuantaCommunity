package com.quanta.demo0.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class UserAuthStatusVO {

    private Integer auditStatus;//-1-未认证过 0-待审核 1-审核通过 2-审核驳回
    private String auditRemark;//审核原因
}
