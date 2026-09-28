CREATE DATABASE IF NOT EXISTS demo;
USE demo;

CREATE TABLE IF NOT EXISTS tb_user (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    openid VARCHAR(128),
    app_user_id VARCHAR(128),
    nick_name VARCHAR(100),
    avatar_url VARCHAR(500),
    auth_status INT DEFAULT 0,
    create_time DATETIME NULL,
    update_time DATETIME NULL,
    is_deleted INT DEFAULT 0,
    is_admin INT DEFAULT 0,
    account_status INT DEFAULT 0,
    UNIQUE KEY uk_tb_user_openid (openid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS tb_user_auth (
    auth_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    identity_type INT,
    real_name VARCHAR(100),
    school_id VARCHAR(100),
    quanta_batch VARCHAR(100),
    quanta_department VARCHAR(100),
    audit_status INT DEFAULT 0,
    audit_remark VARCHAR(500),
    audit_time DATETIME NULL,
    create_time DATETIME NULL,
    update_time DATETIME NULL,
    UNIQUE KEY uk_tb_user_auth_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS user_role (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    role_code VARCHAR(100) NOT NULL,
    created_by BIGINT NULL,
    UNIQUE KEY uk_user_role (user_id, role_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS tb_content (
    content_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    content_type INT,
    title VARCHAR(255),
    content TEXT,
    tags JSON DEFAULT NULL,
    publish_user_id BIGINT,
    audit_status INT DEFAULT 0,
    liked INT DEFAULT 0,
    comment_count INT DEFAULT 0,
    collect_count INT DEFAULT 0,
    create_time DATETIME NULL,
    update_time DATETIME NULL,
    is_deleted INT DEFAULT 0,
    KEY idx_tb_content_author (publish_user_id, audit_status, is_deleted),
    KEY idx_tb_content_update (update_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS tb_content_image (
    image_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    content_id BIGINT NOT NULL,
    image_url VARCHAR(1000),
    sort INT DEFAULT 0,
    create_time DATETIME NULL,
    KEY idx_tb_content_image_content (content_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS tb_content_like (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    content_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    create_time DATETIME NULL,
    UNIQUE KEY uk_tb_content_like (content_id, user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS tb_content_collect (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    content_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    create_time DATETIME NULL,
    UNIQUE KEY uk_tb_content_collect (content_id, user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS tb_user_follow (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    follow_user_id BIGINT NOT NULL,
    create_time DATETIME NULL,
    update_time DATETIME NULL,
    is_deleted INT DEFAULT 0,
    UNIQUE KEY uk_tb_user_follow (user_id, follow_user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS tb_browse_history (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    content_id BIGINT NOT NULL,
    browse_date DATE NOT NULL,
    create_time DATETIME NULL,
    update_time DATETIME NULL,
    is_deleted INT DEFAULT 0,
    UNIQUE KEY uk_tb_browse_history (user_id, content_id, browse_date),
    KEY idx_tb_browse_history_user (user_id, is_deleted, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS tb_question_answer (
    answer_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    question_id BIGINT,
    user_id BIGINT,
    content TEXT,
    like_count INT DEFAULT 0,
    comment_count INT DEFAULT 0,
    is_accepted INT DEFAULT 0,
    audit_status INT DEFAULT 0,
    is_deleted INT DEFAULT 0,
    create_time DATETIME NULL,
    update_time DATETIME NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS tb_bot_policy_doc (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    doc_id VARCHAR(64) NOT NULL,
    title VARCHAR(200) NOT NULL,
    content TEXT NOT NULL,
    is_deleted INT DEFAULT 0,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_tb_bot_policy_doc (doc_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS tb_outbox_event (
    id BIGINT NOT NULL AUTO_INCREMENT,
    event_id CHAR(36) NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id BIGINT NOT NULL,
    payload JSON NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    retry_count INT NOT NULL DEFAULT 0,
    next_retry_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    locked_by VARCHAR(128) NULL,
    locked_until DATETIME(3) NULL,
    last_error VARCHAR(2000) NULL,
    replay_count INT NOT NULL DEFAULT 0,
    last_replay_by BIGINT NULL,
    last_replay_time DATETIME(3) NULL,
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    sent_time DATETIME(3) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_runtime_outbox_event_id (event_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS tb_inbox_event (
    id BIGINT NOT NULL AUTO_INCREMENT,
    event_id CHAR(36) NULL,
    consumer_name VARCHAR(128) NOT NULL,
    outbox_event_id BIGINT NULL,
    event_type VARCHAR(64) NULL,
    aggregate_type VARCHAR(64) NULL,
    aggregate_id BIGINT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PROCESSING',
    retry_count INT NOT NULL DEFAULT 0,
    locked_by VARCHAR(128) NULL,
    locked_until DATETIME(3) NULL,
    last_error VARCHAR(2000) NULL,
    replay_count INT NOT NULL DEFAULT 0,
    last_replay_by BIGINT NULL,
    last_replay_time DATETIME(3) NULL,
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    processed_time DATETIME(3) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_runtime_inbox_consumer_event (consumer_name, event_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS admin_audit_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
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
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    KEY idx_runtime_admin_audit_created (created_at),
    KEY idx_runtime_admin_audit_target (target_type, target_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
