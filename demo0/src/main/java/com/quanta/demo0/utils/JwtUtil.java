package com.quanta.demo0.utils;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import java.security.Key;
import java.util.Date;
import java.util.Map;

/**
 * JWT工具类
 */
public class JwtUtil {

    /**
     * 生成JWT令牌
     * @param secretKey 密钥
     * @param ttlMillis 过期时间
     * @param claims 自定义声明
     * @return JWT令牌
     */
    public static String createJWT(String secretKey, long ttlMillis, Map<String, Object> claims) {
        // 创建签名密钥
        // 注意：这里使用secretKey作为种子生成安全的密钥
        Key key = Keys.hmacShaKeyFor(secretKey.getBytes());

        // 设置过期时间
        long nowMillis = System.currentTimeMillis();
        Date now = new Date(nowMillis);
        Date exp = new Date(nowMillis + ttlMillis);

        // 生成JWT
        return Jwts.builder()
                .setClaims(claims)
                .setIssuedAt(now)
                .setExpiration(exp)
                .signWith(key, SignatureAlgorithm.HS256)
                .compact();
    }

    /**
     * 解析JWT令牌
     * @param secretKey 密钥
     * @param token 令牌
     * @return 自定义声明
     */
    public static Claims parseJWT(String secretKey, String token) {
        // 创建签名密钥
        Key key = Keys.hmacShaKeyFor(secretKey.getBytes());

        // 解析JWT
        return Jwts.parserBuilder()
                .setSigningKey(key)
                .build()
                .parseClaimsJws(token)
                .getBody();
    }
}
