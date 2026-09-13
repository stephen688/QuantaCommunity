package com.quanta.demo0.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Set;

/**
 * 当前登录用户安全上下文。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SecurityContextVO {

    private Long userId;
    private Set<String> roles;
    private Set<String> authorities;
    private Boolean verified;
}
