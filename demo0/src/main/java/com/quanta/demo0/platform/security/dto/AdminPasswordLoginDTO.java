package com.quanta.demo0.platform.security.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/** 管理密码登录输入：不允许客户端指定 userId 或角色，不生成包含密码的 toString。 */
@Getter
@Setter
public class AdminPasswordLoginDTO {
    @NotBlank
    @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._-]{2,63}", message = "账号为3至64位字母、数字、点、下划线或短横线")
    private String username;

    @NotBlank
    @Size(max = 72, message = "密码过长")
    private String password;
}
