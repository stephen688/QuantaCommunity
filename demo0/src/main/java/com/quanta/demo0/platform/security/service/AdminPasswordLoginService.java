package com.quanta.demo0.platform.security.service;

import com.quanta.demo0.platform.security.dto.AdminPasswordLoginDTO;
import com.quanta.demo0.user.vo.UserLoginVO;

/** 管理登录门面：校验凭据与真实账号/管理角色后复用已有会话。 */
public interface AdminPasswordLoginService {
    /** 按来源与规范账号限流，失败不签发会话；source 为 HTTP 实际对端地址。 */
    UserLoginVO login(AdminPasswordLoginDTO input, String source);
}
