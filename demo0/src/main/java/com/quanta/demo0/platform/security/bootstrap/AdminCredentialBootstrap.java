package com.quanta.demo0.platform.security.bootstrap;

import com.quanta.demo0.platform.security.constant.RoleConstants;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 管理端首次凭据初始化工具。
 *
 * <p>该工具只绑定已有的有效用户和已有管理角色，不创建用户、不授予角色、
 * 不更新已有凭据。数据库写入与所有前置校验处于同一事务中，供
 * {@code scripts/init-admin-credential.ps1} 调用，也可以从已构建的
 * classpath 直接运行。</p>
 */
public final class AdminCredentialBootstrap {

    private static final Pattern USERNAME_PATTERN = Pattern.compile(
            "[A-Za-z0-9][A-Za-z0-9._-]{2,63}"
    );
    private static final Set<String> MANAGEMENT_ROLES = Set.of(
            RoleConstants.SUPER_ADMIN,
            RoleConstants.OPERATIONS_ADMIN,
            RoleConstants.CONTENT_AUDITOR
    );
    private static final int BCRYPT_STRENGTH = 12;
    private static final int BCRYPT_MAX_PASSWORD_BYTES = 72;
    private static final String DEFAULT_DB_URL =
            "jdbc:mysql://127.0.0.1:3306/demo"
                    + "?allowPublicKeyRetrieval=true&useSSL=false&serverTimezone=UTC";

    private AdminCredentialBootstrap() {
    }

    /**
     * 为一个既有用户创建第一条管理凭据。
     *
     * @param connection 已连接的目标 MySQL 连接
     * @param userId 既有用户 ID
     * @param rawUsername 管理端账号，按登录服务规则规范化
     * @param password 明文密码字符数组；调用者负责在使用后清理
     * @param passwordEncoder BCrypt 编码器
     * @throws IllegalArgumentException 输入不符合登录 DTO 约束
     * @throws IllegalStateException 用户、角色或凭据状态不满足初始化条件，或数据库操作失败
     */
    public static void initialize(
            Connection connection,
            Long userId,
            String rawUsername,
            char[] password,
            PasswordEncoder passwordEncoder
    ) {
        if (connection == null) {
            throw new IllegalArgumentException("数据库连接不能为空");
        }
        if (passwordEncoder == null) {
            throw new IllegalArgumentException("密码编码器不能为空");
        }
        validateUserId(userId);
        String username = normalizeUsername(rawUsername);
        validatePassword(password);

        boolean originalAutoCommit;
        try {
            originalAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
        } catch (SQLException exception) {
            throw databaseFailure(exception);
        }

        try {
            ensureExistingUsableUser(connection, userId);
            ensureManagementRole(connection, userId);
            ensureNoExistingCredential(connection, userId, username);

            String passwordHash = passwordEncoder.encode(
                    CharBuffer.wrap(password)
            );
            insertCredential(connection, userId, username, passwordHash);
            connection.commit();
        } catch (IllegalStateException exception) {
            rollbackQuietly(connection);
            throw exception;
        } catch (SQLException exception) {
            rollbackQuietly(connection);
            throw databaseFailure(exception);
        } finally {
            restoreAutoCommit(connection, originalAutoCommit);
        }
    }

    /**
     * 命令行入口：账号和用户 ID 可以交互输入，密码只通过不回显的终端输入。
     * 数据库连接参数从环境变量读取，绝不接受密码命令行参数。
     */
    public static void main(String[] args) {
        char[] password = null;
        char[] confirmation = null;
        try {
            Console console = System.console();
            BufferedReader reader = console == null
                    ? new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))
                    : null;

            Long userId = readUserId(args, console, reader);
            String username = readOptionOrPrompt(
                    args,
                    "--username",
                    "管理端账号（3至64位，首字符为字母或数字）：",
                    console,
                    reader
            );
            password = readPassword(
                    "管理端密码（不会回显）：",
                    console
            );
            confirmation = readPassword(
                    "再次输入管理端密码（不会回显）：",
                    console
            );
            if (!Arrays.equals(password, confirmation)) {
                throw new IllegalArgumentException("两次输入的密码不一致");
            }

            try (Connection connection = openConnection()) {
                initialize(
                        connection,
                        userId,
                        username,
                        password,
                        new BCryptPasswordEncoder(BCRYPT_STRENGTH)
                );
            }
            System.out.println("管理端凭据初始化成功；未创建用户、未授予角色、未覆盖既有凭据。");
        } catch (IllegalArgumentException | IllegalStateException exception) {
            System.err.println("管理端凭据初始化失败：" + exception.getMessage());
            System.exit(1);
        } catch (SQLException exception) {
            System.err.println("管理端凭据初始化失败：数据库连接不可用，未写入凭据。");
            System.exit(1);
        } catch (IOException exception) {
            System.err.println("管理端凭据初始化失败：无法读取交互输入。" );
            System.exit(1);
        } finally {
            clear(password);
            clear(confirmation);
        }
    }

    private static void ensureExistingUsableUser(
            Connection connection,
            Long userId
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT account_status, is_deleted "
                        + "FROM tb_user WHERE id = ? FOR UPDATE"
        )) {
            statement.setLong(1, userId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()
                        || result.getInt("account_status") != 0
                        || result.getInt("is_deleted") != 0) {
                    throw new IllegalStateException("用户不存在或不可用");
                }
            }
        }
    }

    private static void ensureManagementRole(
            Connection connection,
            Long userId
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT role_code FROM user_role "
                        + "WHERE user_id = ? "
                        + "AND role_code IN ('SUPER_ADMIN', 'OPERATIONS_ADMIN', 'CONTENT_AUDITOR') "
                        + "FOR UPDATE"
        )) {
            statement.setLong(1, userId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    if (MANAGEMENT_ROLES.contains(result.getString("role_code"))) {
                        return;
                    }
                }
                throw new IllegalStateException("用户没有管理角色，未创建凭据");
            }
        }
    }

    private static void ensureNoExistingCredential(
            Connection connection,
            Long userId,
            String username
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT user_id FROM admin_credential "
                        + "WHERE user_id = ? OR username = ? LIMIT 1 FOR UPDATE"
        )) {
            statement.setLong(1, userId);
            statement.setString(2, username);
            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) {
                    throw new IllegalStateException(
                            "已有管理凭据，工具不会覆盖"
                    );
                }
            }
        }
    }

    private static void insertCredential(
            Connection connection,
            Long userId,
            String username,
            String passwordHash
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO admin_credential "
                        + "(user_id, username, password_hash, enabled) "
                        + "VALUES (?, ?, ?, 1)"
        )) {
            statement.setLong(1, userId);
            statement.setString(2, username);
            statement.setString(3, passwordHash);
            if (statement.executeUpdate() != 1) {
                throw new IllegalStateException("凭据没有成功写入");
            }
        }
    }

    private static Connection openConnection() throws SQLException {
        String url = environmentOrDefault(
                "ADMIN_DB_URL",
                "SPRING_DATASOURCE_URL",
                DEFAULT_DB_URL
        );
        String username = environmentOrDefault(
                "ADMIN_DB_USERNAME",
                "SPRING_DATASOURCE_USERNAME",
                "root"
        );
        String password = System.getenv("DB_PASSWORD");
        if (password == null || password.isBlank()) {
            throw new IllegalStateException(
                    "未设置 DB_PASSWORD，拒绝在未知数据库凭据下继续"
            );
        }
        return DriverManager.getConnection(url, username, password);
    }

    private static String environmentOrDefault(
            String primary,
            String fallback,
            String defaultValue
    ) {
        String primaryValue = System.getenv(primary);
        if (primaryValue != null && !primaryValue.isBlank()) {
            return primaryValue;
        }
        String fallbackValue = System.getenv(fallback);
        return fallbackValue == null || fallbackValue.isBlank()
                ? defaultValue
                : fallbackValue;
    }

    private static Long readUserId(
            String[] args,
            Console console,
            BufferedReader reader
    ) throws IOException {
        String value = readOptionOrPrompt(
                args,
                "--user-id",
                "已有用户 ID（不会创建用户）：",
                console,
                reader
        );
        try {
            long userId = Long.parseLong(value.trim());
            validateUserId(userId);
            return userId;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("用户 ID 必须是正整数");
        }
    }

    private static String readOptionOrPrompt(
            String[] args,
            String option,
            String prompt,
            Console console,
            BufferedReader reader
    ) throws IOException {
        for (int index = 0; index + 1 < args.length; index++) {
            if (option.equals(args[index])) {
                return args[index + 1];
            }
        }
        if (console != null) {
            String value = console.readLine(prompt);
            if (value == null) {
                throw new IOException("输入结束");
            }
            return value;
        }
        System.out.print(prompt);
        String value = reader.readLine();
        if (value == null) {
            throw new IOException("输入结束");
        }
        return value;
    }

    private static char[] readPassword(
            String prompt,
            Console console
    ) throws IOException {
        if (console == null) {
            throw new IOException(
                    "当前终端不支持隐藏密码输入，请在 Windows Terminal 或本地交互终端中运行"
            );
        }
        char[] value = console.readPassword(prompt);
        if (value == null) {
            throw new IOException("输入结束");
        }
        return value;
    }

    private static String normalizeUsername(String rawUsername) {
        if (rawUsername == null) {
            throw new IllegalArgumentException("账号不能为空");
        }
        String username = rawUsername.trim().toLowerCase(Locale.ROOT);
        if (!USERNAME_PATTERN.matcher(username).matches()) {
            throw new IllegalArgumentException(
                    "账号为3至64位字母、数字、点、下划线或短横线，且首字符必须为字母或数字"
            );
        }
        return username;
    }

    private static void validatePassword(char[] password) {
        if (password == null || password.length == 0
                || CharBuffer.wrap(password).chars().allMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("密码不能为空");
        }
        int byteLength = StandardCharsets.UTF_8
                .encode(CharBuffer.wrap(password))
                .remaining();
        if (byteLength > BCRYPT_MAX_PASSWORD_BYTES) {
            throw new IllegalArgumentException("密码 UTF-8 字节数不能超过72");
        }
    }

    private static void validateUserId(Long userId) {
        if (userId == null || userId <= 0) {
            throw new IllegalArgumentException("用户 ID 必须是正整数");
        }
    }

    private static IllegalStateException databaseFailure(SQLException exception) {
        return new IllegalStateException("数据库操作失败，未写入凭据", exception);
    }

    private static void rollbackQuietly(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // 保留原始失败原因；命令行只输出不含凭据的摘要。
        }
    }

    private static void restoreAutoCommit(
            Connection connection,
            boolean originalAutoCommit
    ) {
        try {
            connection.setAutoCommit(originalAutoCommit);
        } catch (SQLException ignored) {
            // 连接即将由调用者关闭，不能覆盖已确定的业务结论。
        }
    }

    private static void clear(char[] value) {
        if (value != null) {
            Arrays.fill(value, '\0');
        }
    }
}
