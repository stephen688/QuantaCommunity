package com.quanta.demo0.security;

import com.quanta.demo0.entity.BaseContext;
import com.quanta.demo0.properties.JwtProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 可选JWT认证过滤器。
 *
 * 核心职责：
 * 1. 请求没有Token时保持匿名；
 * 2. 请求携带Token时调用统一认证服务；
 * 3. 认证成功后把用户身份写入SecurityContext；
 * 4. 同时写入BaseContext，兼容现有业务代码。
 *
 * 注意：
 * 该过滤器只负责识别用户，不负责决定接口能否访问。
 * 接口访问权限由Spring Security授权配置统一处理。
 */
@Component
@Slf4j
public class OptionalJwtAuthenticationFilter
        extends OncePerRequestFilter {

    @Autowired
    private JwtProperties jwtProperties;

    @Autowired
    private TokenAuthenticationService tokenAuthenticationService;

    /**
     * 每个HTTP请求只执行一次。
     */
    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {

        /*
         * 请求线程可能被服务器重复使用，
         * 进入过滤器时先清理可能残留的旧用户ID。
         */
        BaseContext.removeCurrentId();

        try {
            /*
             * OPTIONS属于浏览器跨域预检请求，
             * 不需要进行Token认证。
             */
            if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
                filterChain.doFilter(request, response);
                return;
            }

            String headerName =
                    jwtProperties.getUserTokenName();

            String token =
                    request.getHeader(headerName);

            /*
             * 没有Token时不直接返回401，
             * 而是继续作为匿名用户访问。
             *
             * 如果目标接口要求登录，
             * 后面的Spring Security权限规则会返回401。
             */
            if (token == null || token.isBlank()) {
                filterChain.doFilter(request, response);
                return;
            }

            try {
                /*
                 * JWT、Redis登录态、封禁状态和数据库用户状态，
                 * 全部交给统一认证服务处理。
                 */
                AuthenticatedUser authenticatedUser =
                        tokenAuthenticationService.authenticate(token);

                /*
                 * 把项目中的角色和权限，
                 * 转换为Spring Security能够识别的权限对象。
                 */
                Collection<GrantedAuthority> grantedAuthorities =
                        buildGrantedAuthorities(authenticatedUser);

                /*
                 * 创建Spring Security认证对象。
                 *
                 * principal：当前登录用户
                 * credentials：不保存Token，避免敏感数据长期存在
                 * authorities：用户拥有的角色和权限
                 */
                UsernamePasswordAuthenticationToken authentication =
                        new UsernamePasswordAuthenticationToken(
                                authenticatedUser,
                                null,
                                grantedAuthorities
                        );

                /*
                 * 保存当前请求的来源信息，
                 * 例如客户端IP和Session信息。
                 */
                authentication.setDetails(
                        new WebAuthenticationDetailsSource()
                                .buildDetails(request)
                );

                /*
                 * 创建一个全新的SecurityContext，
                 * 保存当前已经认证的用户。
                 */
                SecurityContext securityContext =
                        SecurityContextHolder.createEmptyContext();

                securityContext.setAuthentication(authentication);
                SecurityContextHolder.setContext(securityContext);

                /*
                 * 兼容项目中现有的BaseContext调用。
                 *
                 * 后续业务代码可以逐步迁移，
                 * 现在不需要一次修改所有getCurrentId()。
                 */
                BaseContext.setCurrentId(
                        authenticatedUser.getUserId()
                );

            } catch (TokenAuthenticationException exception) {
                /*
                 * Token无效时清空身份，继续作为匿名用户。
                 *
                 * 公开接口可以继续访问；
                 * 受保护接口会在后续授权阶段返回401。
                 */
                SecurityContextHolder.clearContext();
                BaseContext.removeCurrentId();

                log.warn(
                        "HTTP Token认证失败，uri={}，reason={}",
                        request.getRequestURI(),
                        exception.getReason()
                );
            } catch (Exception exception) {
                /*
                 * Redis、数据库等认证依赖发生异常时，
                 * 不能把用户当成已登录用户。
                 */
                SecurityContextHolder.clearContext();
                BaseContext.removeCurrentId();

                log.error(
                        "HTTP Token认证发生系统异常，uri={}，message={}",
                        request.getRequestURI(),
                        exception.getMessage()
                );
            }

            /*
             * 继续执行后续过滤器、权限判断和Controller。
             */
            filterChain.doFilter(request, response);
        } finally {
            /*
             * HTTP请求结束后必须清理BaseContext，
             * 防止线程复用导致用户身份串号。
             *
             * SecurityContext由Spring Security的过滤器链负责清理。
             */
            BaseContext.removeCurrentId();
        }
    }

    /**
     * 将项目角色和业务权限转换为Spring Security权限。
     */
    private Collection<GrantedAuthority> buildGrantedAuthorities(
            AuthenticatedUser authenticatedUser
    ) {
        Set<String> authorityNames = new LinkedHashSet<>();

        /*
         * Spring Security的角色默认使用ROLE_前缀。
         *
         * 例如：
         * USER          -> ROLE_USER
         * VERIFIED_USER -> ROLE_VERIFIED_USER
         * SUPER_ADMIN   -> ROLE_SUPER_ADMIN
         */
        if (authenticatedUser.getRoles() != null) {
            for (String role : authenticatedUser.getRoles()) {
                if (role == null || role.isBlank()) {
                    continue;
                }

                if (role.startsWith("ROLE_")) {
                    authorityNames.add(role);
                } else {
                    authorityNames.add("ROLE_" + role);
                }
            }
        }

        /*
         * 业务权限不添加ROLE_前缀。
         *
         * 例如：
         * CONTENT_AUDIT
         * CONTENT_DELETE
         * EVENT_REPLAY
         */
        if (authenticatedUser.getAuthorities() != null) {
            for (String authority :
                    authenticatedUser.getAuthorities()) {

                if (authority != null
                        && !authority.isBlank()) {
                    authorityNames.add(authority);
                }
            }
        }

        return authorityNames.stream()
                .<GrantedAuthority>map(SimpleGrantedAuthority::new)
                .toList();
    }
}
