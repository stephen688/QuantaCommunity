package com.quanta.demo0.user.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 账号事实快照，仅供安全、身份和领域校验，避免暴露持久化实体。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserAccountVO {
    private Long id;
    private String openid;
    private String nickName;
    private String avatarUrl;
    private Integer accountStatus;
    private Integer isDeleted;
}
