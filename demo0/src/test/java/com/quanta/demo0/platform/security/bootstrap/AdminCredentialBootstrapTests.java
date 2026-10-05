package com.quanta.demo0.platform.security.bootstrap;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 管理凭据初始化工具的 MySQL 契约测试。
 *
 * <p>使用隔离数据库验证用户、角色和既有凭据边界，避免用 mock 把
 * “没有覆盖旧密码”误判成成功。</p>
 */
@Testcontainers
class AdminCredentialBootstrapTests {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.43")
            .withDatabaseName("bootstrap")
            .withUsername("bootstrap")
            .withPassword("bootstrap");

    private BCryptPasswordEncoder passwordEncoder;

    @BeforeEach
    void setUp() throws Exception {
        passwordEncoder = new BCryptPasswordEncoder(12);
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS tb_user ("
                    + "id BIGINT PRIMARY KEY, account_status TINYINT NOT NULL DEFAULT 0,"
                    + "is_deleted TINYINT NOT NULL DEFAULT 0)");
            statement.execute("CREATE TABLE IF NOT EXISTS user_role ("
                    + "user_id BIGINT NOT NULL, role_code VARCHAR(64) NOT NULL,"
                    + "PRIMARY KEY (user_id, role_code))");
            statement.execute("CREATE TABLE IF NOT EXISTS admin_credential ("
                    + "user_id BIGINT PRIMARY KEY, username VARCHAR(64) NOT NULL UNIQUE,"
                    + "password_hash VARCHAR(100) NOT NULL, enabled TINYINT NOT NULL DEFAULT 1)");
            statement.execute("DELETE FROM admin_credential");
            statement.execute("DELETE FROM user_role");
            statement.execute("DELETE FROM tb_user");
        }
    }

    @Test
    void rejectsUserThatDoesNotExistWithoutWritingCredential() throws Exception {
        try (Connection connection = connection()) {
            assertThatThrownBy(() -> AdminCredentialBootstrap.initialize(
                    connection,
                    404L,
                    "missing-admin",
                    "Safe-pass-1".toCharArray(),
                    passwordEncoder
            ))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("用户不存在或不可用");

            assertThat(countCredentials(connection)).isZero();
        }
    }

    @Test
    void rejectsExistingUserWithoutManagementRole() throws Exception {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO tb_user(id, account_status, is_deleted) VALUES(7, 0, 0)");

            assertThatThrownBy(() -> AdminCredentialBootstrap.initialize(
                    connection,
                    7L,
                    "no-role-admin",
                    "Safe-pass-1".toCharArray(),
                    passwordEncoder
            ))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("没有管理角色");

            assertThat(countCredentials(connection)).isZero();
        }
    }

    @Test
    void storesBcrypt12HashAndNeverOverwritesExistingCredential() throws Exception {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO tb_user(id, account_status, is_deleted) VALUES(7, 0, 0)");
            statement.execute("INSERT INTO user_role(user_id, role_code) VALUES(7, 'SUPER_ADMIN')");

            char[] password = "Safe-pass-1".toCharArray();
            AdminCredentialBootstrap.initialize(
                    connection,
                    7L,
                    "Admin-One",
                    password,
                    passwordEncoder
            );

            String storedHash = credentialHash(connection);
            assertThat(passwordEncoder.matches("Safe-pass-1", storedHash)).isTrue();
            assertThat(username(connection)).isEqualTo("admin-one");
            assertThat(countRoles(connection)).isEqualTo(1);

            assertThatThrownBy(() -> AdminCredentialBootstrap.initialize(
                    connection,
                    7L,
                    "admin-two",
                    "Different-pass-2".toCharArray(),
                    passwordEncoder
            ))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("已有管理凭据");

            assertThat(passwordEncoder.matches("Safe-pass-1", credentialHash(connection))).isTrue();
            assertThat(countCredentials(connection)).isEqualTo(1);
            assertThat(username(connection)).isEqualTo("admin-one");
        }
    }

    private Connection connection() throws Exception {
        return DriverManager.getConnection(
                MYSQL.getJdbcUrl(),
                MYSQL.getUsername(),
                MYSQL.getPassword()
        );
    }

    private int countCredentials(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM admin_credential")) {
            result.next();
            return result.getInt(1);
        }
    }

    private int countRoles(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM user_role WHERE user_id=7")) {
            result.next();
            return result.getInt(1);
        }
    }

    private String credentialHash(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT password_hash FROM admin_credential WHERE user_id=7")) {
            result.next();
            return result.getString(1);
        }
    }

    private String username(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT username FROM admin_credential WHERE user_id=7")) {
            result.next();
            return result.getString(1);
        }
    }
}
