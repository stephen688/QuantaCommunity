-- 管理端密码凭据：绑定已有用户与角色，不创建默认账号或默认密码。
-- 可重复执行；回滚前先禁用密码登录入口，不能删除原 tb_user/user_role。
CREATE TABLE IF NOT EXISTS admin_credential (
    user_id BIGINT NOT NULL PRIMARY KEY,
    username VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    password_hash VARCHAR(100) CHARACTER SET ascii NOT NULL,
    enabled TINYINT NOT NULL DEFAULT 1,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_admin_credential_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
