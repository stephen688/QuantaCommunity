-- HTTP 提交幂等凭证：业务表、凭证和 Outbox 在同一事务中写入。
CREATE TABLE IF NOT EXISTS tb_http_submission (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    scene VARCHAR(32) NOT NULL,
    submission_token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status VARCHAR(16) NOT NULL,
    response_data JSON NULL,
    create_time DATETIME(3) NOT NULL,
    expires_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_scene_token (user_id, scene, submission_token),
    KEY idx_status_expiry (status, expires_at)
) ENGINE=InnoDB;
