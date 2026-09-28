package com.quanta.demo0.platform.security.constant;

public class JwtClaimsConstant {


    public static final String USER_ID = "userId";

    /**
     * 令牌类型 claim 键（C-5 service token 特判依据）。
     */
    public static final String TOKEN_TYPE = "tokenType";

    /**
     * 服务间令牌类型值：bot 系统账号专用，免 Redis 会话校验。
     */
    public static final String SERVICE_TOKEN_TYPE = "service";

}
