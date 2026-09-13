package com.quanta.demo0.config;

import com.quanta.demo0.properties.SecurityProperties;
import com.quanta.demo0.security.OptionalJwtAuthenticationFilter;
import com.quanta.demo0.security.SecurityAccessDeniedHandler;
import com.quanta.demo0.security.SecurityAuthenticationEntryPoint;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * Spring Security安全配置。
 *
 * 核心职责：
 * 1. 注册JWT认证过滤器；
 * 2. 配置公开、登录和管理员接口；
 * 3. 统一处理401和403；
 * 4. 保持无服务端Session的Token登录模式。
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfiguration {

    @Autowired
    private OptionalJwtAuthenticationFilter
            optionalJwtAuthenticationFilter;

    @Autowired
    private SecurityAuthenticationEntryPoint
            securityAuthenticationEntryPoint;

    @Autowired
    private SecurityAccessDeniedHandler
            securityAccessDeniedHandler;


    /**
     * HTTP跨域配置。
     *
     * Spring Security会使用该配置判断浏览器请求的Origin
     * 是否允许访问当前后端。
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource(
            SecurityProperties securityProperties
    ) {
        CorsConfiguration configuration =
                new CorsConfiguration();

        /*
         * 从application.yml读取允许的前端来源。
         */
        configuration.setAllowedOriginPatterns(
                securityProperties
                        .getCors()
                        .getAllowedOriginPatterns()
        );

        /*
         * 允许项目当前使用的HTTP请求方式。
         */
        configuration.setAllowedMethods(List.of(
                "GET",
                "POST",
                "PUT",
                "DELETE",
                "PATCH",
                "OPTIONS"
        ));

        /*
         * 允许authorization、Content-Type等请求头。
         */
        configuration.setAllowedHeaders(List.of("*"));

        /*
         * 当前Token放在自定义Header中，
         * 不依赖跨域Cookie，因此不携带浏览器凭证。
         */
        configuration.setAllowCredentials(false);

        /*
         * 浏览器可以缓存预检请求结果1小时。
         */
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source =
                new UrlBasedCorsConfigurationSource();

        /*
         * 以上跨域规则适用于所有HTTP接口。
         */
        source.registerCorsConfiguration(
                "/**",
                configuration
        );

        return source;
    }




    /**
     * 配置HTTP安全过滤器链。
     */
    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            SecurityProperties securityProperties
    ) throws Exception {

        http
                /*
                 * 当前Token通过自定义Header传递，
                 * 不依赖Cookie维持登录状态，因此关闭CSRF。
                 */
                .csrf(AbstractHttpConfigurer::disable)

                /*
                 * 暂时继续使用项目现有跨域配置。
                 */
                .cors(Customizer.withDefaults())

                /*
                 * Spring Security不创建HttpSession，
                 * 每次请求都重新检查Token。
                 */
                .sessionManagement(session ->
                        session.sessionCreationPolicy(
                                SessionCreationPolicy.STATELESS
                        )
                )

                /*
                 * 关闭Spring Security默认登录方式。
                 */
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)

                /*
                 * 配置统一的401和403返回。
                 */
                .exceptionHandling(exception ->
                        exception
                                .authenticationEntryPoint(
                                        securityAuthenticationEntryPoint
                                )
                                .accessDeniedHandler(
                                        securityAccessDeniedHandler
                                )
                )

                /*
                 * 配置不同接口的访问要求。
                 *
                 * 规则从上往下匹配，
                 * 越具体的规则要写在越前面。
                 */
                .authorizeHttpRequests(authorize -> {
                                /*
                                 * 浏览器跨域预检请求不要求登录。
                                 */
                                authorize.requestMatchers(
                                        HttpMethod.OPTIONS,
                                        "/**"
                                ).permitAll();

                                /*
                                 * 登录、错误页面公开访问。
                                 */
                                authorize.requestMatchers(
                                        "/user/login",
                                        "/error"
                                ).permitAll();

                                /*
                                 * API文档是否公开由配置决定，不再无条件放行。
                                 *
                                 * 默认关闭（生产环境不要设置 API_DOCS_ENABLED=true）；
                                 * 关闭后文档路径由下方的 anyRequest().authenticated()
                                 * 兜底，避免生产环境裸暴露接口清单。
                                 */
                                if (Boolean.TRUE.equals(
                                        securityProperties.getApiDocsEnabled())) {
                                    authorize.requestMatchers(
                                            "/v3/api-docs",
                                            "/v3/api-docs/**",
                                            "/doc.html",
                                            "/swagger-ui.html",
                                            "/swagger-ui/**",
                                            "/webjars/**"
                                    ).permitAll();
                                }

                                /*
                                 * WebSocket的HTTP握手先放行，
                                 * 真正认证在STOMP CONNECT阶段完成。
                                 */
                                authorize.requestMatchers(
                                        "/ws",
                                        "/ws/**"
                                ).permitAll();

                                /*
                                 * 可选鉴权接口：
                                 * 没登录可以访问；
                                 * 登录后可以返回个性化内容。
                                 */
                                authorize.requestMatchers(
                                        "/content/recommend",
                                        "/search/content",
                                        "/search/trending"
                                ).permitAll();

                                /*
                                 * 事件管理接口已经完成方法级授权。
                                 *
                                 * URL层只检查是否登录，
                                 * 具体的查看和重放权限由Controller上的
                                 * @PreAuthorize负责判断。
                                 */
                                authorize.requestMatchers(
                                        "/admin/events/**"
                                ).authenticated();

                                /*
                                 * 内容管理接口已经完成方法级授权。
                                 */
                                authorize.requestMatchers(
                                        "/admin/content/**",
                                        "/admin/answer/**",
                                        "/admin/comment/**",
                                        "/admin/moderation/**"
                                ).authenticated();

                                /*
                                 * 用户治理和身份审核接口已经完成方法级授权。
                                 */
                                authorize.requestMatchers(
                                        "/admin/user/**",
                                        "/admin/identityExam/**"
                                ).authenticated();

                                /*
                                 * 角色管理和审计日志接口已完成方法级授权，
                                 * URL层只检查是否登录，具体权限由Controller的@PreAuthorize判断。
                                 */
                                authorize.requestMatchers(
                                        "/admin/roles/**",
                                        "/admin/audit-logs/**"
                                ).authenticated();

                                /*
                                 * 尚未完成方法级授权的其他管理接口，
                                 * 继续只允许SUPER_ADMIN访问。
                                 *
                                 * 这是迁移期间的安全兜底，不能提前删除。
                                 */
                                authorize.requestMatchers(
                                        "/admin/**"
                                ).hasRole("SUPER_ADMIN");

                                /*
                                 * 除了上面的公开接口，
                                 * 其他接口暂时都要求登录。
                                 */
                                authorize.anyRequest().authenticated();
                })

                /*
                 * 在Spring Security处理用户名密码登录之前，
                 * 先通过项目自己的JWT识别当前用户。
                 */
                .addFilterBefore(
                        optionalJwtAuthenticationFilter,
                        UsernamePasswordAuthenticationFilter.class
                );

        return http.build();
    }
}
