package com.quanta.demo0.vo;


import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserLoginVO implements Serializable {
    private Long id;
    private String openid;
    private String token;
    private String nickName;
    private String avatarUrl;
}
