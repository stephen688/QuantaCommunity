SET NAMES utf8mb4;

CREATE DATABASE IF NOT EXISTS demo;
USE demo;

CREATE TABLE IF NOT EXISTS tb_user (
    id BIGINT NOT NULL PRIMARY KEY AUTO_INCREMENT,
    openid VARCHAR(128) DEFAULT NULL,
    app_user_id VARCHAR(128) DEFAULT NULL,
    nick_name VARCHAR(100) DEFAULT NULL,
    avatar_url VARCHAR(500) DEFAULT NULL,
    auth_status INT NOT NULL DEFAULT 0,
    create_time DATETIME(3) DEFAULT NULL,
    update_time DATETIME(3) DEFAULT NULL,
    is_deleted INT NOT NULL DEFAULT 0,
    is_admin INT NOT NULL DEFAULT 0,
    account_status INT NOT NULL DEFAULT 0,
    UNIQUE KEY uk_submission_user_openid (openid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS tb_user_auth (
    auth_id BIGINT NOT NULL PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    identity_type INT DEFAULT NULL,
    real_name VARCHAR(100) DEFAULT NULL,
    school_id VARCHAR(100) DEFAULT NULL,
    quanta_batch VARCHAR(100) DEFAULT NULL,
    quanta_department VARCHAR(100) DEFAULT NULL,
    audit_status INT NOT NULL DEFAULT 0,
    audit_remark VARCHAR(500) DEFAULT NULL,
    audit_time DATETIME(3) DEFAULT NULL,
    create_time DATETIME(3) DEFAULT NULL,
    update_time DATETIME(3) DEFAULT NULL,
    UNIQUE KEY uk_submission_user_auth (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS user_role (
    id BIGINT NOT NULL PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    role_code VARCHAR(100) NOT NULL,
    created_by BIGINT DEFAULT NULL,
    UNIQUE KEY uk_submission_user_role (user_id, role_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS tb_content (
    content_id BIGINT NOT NULL PRIMARY KEY AUTO_INCREMENT,
    content_type INT NOT NULL DEFAULT 1,
    title VARCHAR(255) DEFAULT NULL,
    content VARCHAR(2000) DEFAULT NULL,
    tags JSON DEFAULT NULL,
    publish_user_id BIGINT NOT NULL,
    audit_status INT NOT NULL DEFAULT 0,
    liked INT NOT NULL DEFAULT 0,
    comment_count INT NOT NULL DEFAULT 0,
    collect_count INT NOT NULL DEFAULT 0,
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    is_deleted INT NOT NULL DEFAULT 0,
    KEY idx_submission_content_author (publish_user_id, audit_status, is_deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS tb_content_image (
    image_id BIGINT NOT NULL PRIMARY KEY AUTO_INCREMENT,
    content_id BIGINT NOT NULL,
    image_url VARCHAR(1000) NOT NULL,
    sort INT NOT NULL DEFAULT 0,
    create_time DATETIME(3) DEFAULT NULL,
    KEY idx_submission_content_image (content_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS tb_comment_like (
    id BIGINT NOT NULL PRIMARY KEY AUTO_INCREMENT,
    comment_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_submission_comment_user (comment_id, user_id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS tb_browse_history (
    id BIGINT NOT NULL PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    content_id BIGINT NOT NULL,
    browse_date DATE NOT NULL,
    create_time DATETIME(3) DEFAULT NULL,
    update_time DATETIME(3) DEFAULT NULL,
    is_deleted INT NOT NULL DEFAULT 0,
    UNIQUE KEY uk_submission_browse_history (user_id, content_id, browse_date),
    KEY idx_submission_browse_user (user_id, is_deleted, create_time)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS tb_question_answer (
    answer_id BIGINT NOT NULL PRIMARY KEY AUTO_INCREMENT,
    question_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    content VARCHAR(2000) DEFAULT NULL,
    like_count INT NOT NULL DEFAULT 0,
    comment_count INT NOT NULL DEFAULT 0,
    is_accepted INT NOT NULL DEFAULT 0,
    audit_status INT NOT NULL DEFAULT 0,
    reject_reason VARCHAR(500) DEFAULT NULL,
    is_deleted INT NOT NULL DEFAULT 0,
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS tb_content_comment (
    comment_id BIGINT NOT NULL PRIMARY KEY AUTO_INCREMENT,
    content_id BIGINT NOT NULL,
    answer_id BIGINT DEFAULT NULL,
    parent_id BIGINT DEFAULT NULL,
    reply_comment_id BIGINT DEFAULT NULL,
    reply_user_id BIGINT DEFAULT NULL,
    user_id BIGINT NOT NULL,
    content VARCHAR(2000) DEFAULT NULL,
    like_count INT NOT NULL DEFAULT 0,
    audit_status INT NOT NULL DEFAULT 0,
    reject_reason VARCHAR(500) DEFAULT NULL,
    audit_time DATETIME(3) DEFAULT NULL,
    audit_user_id BIGINT DEFAULT NULL,
    is_deleted INT NOT NULL DEFAULT 0,
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    KEY idx_submission_comment_content (content_id, audit_status, is_deleted),
    KEY idx_submission_comment_parent (parent_id, audit_status, is_deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS tb_comment_image (
    id BIGINT NOT NULL PRIMARY KEY AUTO_INCREMENT,
    comment_id BIGINT NOT NULL,
    image_url VARCHAR(1000) NOT NULL,
    sort INT NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS tb_notification (
    id BIGINT NOT NULL PRIMARY KEY AUTO_INCREMENT,
    recipient_user_id BIGINT NOT NULL,
    actor_user_id BIGINT DEFAULT NULL,
    type VARCHAR(64) NOT NULL,
    content VARCHAR(500) NOT NULL,
    payload JSON DEFAULT NULL,
    is_read INT NOT NULL DEFAULT 0,
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    is_deleted INT NOT NULL DEFAULT 0
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS tb_outbox_event (
    id BIGINT NOT NULL PRIMARY KEY AUTO_INCREMENT,
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_type VARCHAR(128) NOT NULL,
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id BIGINT NOT NULL,
    payload JSON NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    retry_count INT NOT NULL DEFAULT 0,
    next_retry_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    locked_by VARCHAR(128) DEFAULT NULL,
    locked_until DATETIME(3) DEFAULT NULL,
    last_error VARCHAR(2000) DEFAULT NULL,
    replay_count INT NOT NULL DEFAULT 0,
    last_replay_by BIGINT DEFAULT NULL,
    last_replay_time DATETIME(3) DEFAULT NULL,
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    sent_time DATETIME(3) DEFAULT NULL,
    UNIQUE KEY uk_submission_outbox_event_id (event_id),
    KEY idx_submission_outbox_aggregate (aggregate_type, aggregate_id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS tb_inbox_event (
    id BIGINT NOT NULL PRIMARY KEY AUTO_INCREMENT,
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL,
    consumer_name VARCHAR(128) NOT NULL,
    outbox_event_id BIGINT DEFAULT NULL,
    event_type VARCHAR(128) DEFAULT NULL,
    aggregate_type VARCHAR(64) DEFAULT NULL,
    aggregate_id BIGINT DEFAULT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PROCESSING',
    retry_count INT NOT NULL DEFAULT 0,
    locked_by VARCHAR(128) DEFAULT NULL,
    locked_until DATETIME(3) DEFAULT NULL,
    last_error VARCHAR(2000) DEFAULT NULL,
    replay_count INT NOT NULL DEFAULT 0,
    last_replay_by BIGINT DEFAULT NULL,
    last_replay_time DATETIME(3) DEFAULT NULL,
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    processed_time DATETIME(3) DEFAULT NULL,
    UNIQUE KEY uk_submission_inbox_consumer_event (consumer_name, event_id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS tb_http_submission (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    scene VARCHAR(32) NOT NULL,
    submission_token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status VARCHAR(16) NOT NULL,
    response_data JSON DEFAULT NULL,
    create_time DATETIME(3) NOT NULL,
    expires_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_submission_user_scene_token (user_id, scene, submission_token),
    KEY idx_submission_status_expiry (status, expires_at)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS admin_audit_log (
    id BIGINT NOT NULL PRIMARY KEY AUTO_INCREMENT,
    request_id VARCHAR(128),
    operator_id BIGINT,
    operator_roles VARCHAR(500),
    action VARCHAR(100) NOT NULL,
    target_type VARCHAR(100),
    target_id VARCHAR(128),
    http_method VARCHAR(20),
    request_path VARCHAR(500),
    before_summary VARCHAR(2000),
    after_summary VARCHAR(2000),
    result_status VARCHAR(30) NOT NULL,
    error_code VARCHAR(100),
    error_message VARCHAR(1000),
    client_ip VARCHAR(128),
    user_agent VARCHAR(1000),
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB;
