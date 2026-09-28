package com.quanta.demo0.devtools;

import com.quanta.demo0.constant.JwtClaimsConstant;
import com.quanta.demo0.platform.security.properties.JwtProperties;
import com.quanta.demo0.platform.security.properties.QuantabotProperties;
import com.quanta.demo0.utils.JwtUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;

import java.util.HashMap;
import java.util.Map;

/**
 * dev 工具：生成 bot 一年期 service token。
 *
 * 手动运行：
 * mvn -f demo0/pom.xml test -Dtest="BotServiceTokenGeneratorTest" \
 *     -Dbot.service-token.generate=true
 *
 * 输出的 token 只写入本地私密配置，不提交到 git。
 */
class BotServiceTokenGeneratorTest {

    @Test
    @EnabledIfSystemProperty(
            named = "bot.service-token.generate",
            matches = "true"
    )
    void generateBotServiceToken() throws IOException {
        StandardEnvironment environment = loadLocalEnvironment();
        Binder binder = Binder.get(environment);
        JwtProperties jwtProperties = binder.bind("quanta.jwt", JwtProperties.class)
                .orElseThrow(() -> new IllegalStateException("缺少 quanta.jwt 配置"));
        QuantabotProperties quantabotProperties = binder.bind("quantabot", QuantabotProperties.class)
                .orElseGet(QuantabotProperties::new);

        if (jwtProperties.getUserSecretKey() == null
                || jwtProperties.getUserSecretKey().isBlank()
                || jwtProperties.getUserSecretKey().startsWith("${")) {
            throw new IllegalStateException("缺少 JWT_USER_SECRET_KEY 或 application-local-secret.yml");
        }

        Map<String, Object> claims = new HashMap<>();
        claims.put(
                JwtClaimsConstant.USER_ID,
                quantabotProperties.getBotUserId()
        );
        claims.put(
                JwtClaimsConstant.TOKEN_TYPE,
                JwtClaimsConstant.SERVICE_TOKEN_TYPE
        );

        long oneYearMillis = 365L * 24 * 60 * 60 * 1000;
        String token = JwtUtil.createJWT(
                jwtProperties.getUserSecretKey(),
                oneYearMillis,
                claims
        );

        System.out.println();
        System.out.println("==== BOT SERVICE TOKEN（1 年期，userId=10000）====");
        System.out.println(token);
        System.out.println("=================================================");
    }

    /**
     * 只加载生成 token 所需的本地配置，避免开发工具启动完整 Spring 应用。
     */
    private StandardEnvironment loadLocalEnvironment() throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();

        for (var propertySource : loader.load("application", new ClassPathResource("application.yml"))) {
            environment.getPropertySources().addLast(propertySource);
        }

        ClassPathResource localSecret = new ClassPathResource("application-local-secret.yml");
        if (localSecret.exists()) {
            for (var propertySource : loader.load("application-local-secret", localSecret)) {
                environment.getPropertySources().addFirst(propertySource);
            }
        }
        return environment;
    }
}
