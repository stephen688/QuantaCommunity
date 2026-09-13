SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS tb_user (
    id BIGINT NOT NULL PRIMARY KEY,
    nick_name VARCHAR(64) DEFAULT NULL
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS tb_content (
    content_id BIGINT NOT NULL PRIMARY KEY,
    content_type INT NOT NULL DEFAULT 1,
    title VARCHAR(100) DEFAULT NULL,
    content VARCHAR(1000) DEFAULT NULL,
    publish_user_id BIGINT NOT NULL,
    audit_status INT NOT NULL DEFAULT 0,
    liked INT NOT NULL DEFAULT 0,
    comment_count INT NOT NULL DEFAULT 0,
    collect_count INT NOT NULL DEFAULT 0,
    is_deleted INT NOT NULL DEFAULT 0,
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS tb_question_answer (
    answer_id BIGINT NOT NULL PRIMARY KEY,
    question_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    content VARCHAR(1000) DEFAULT NULL,
    like_count INT NOT NULL DEFAULT 0,
    comment_count INT NOT NULL DEFAULT 0,
    is_accepted INT NOT NULL DEFAULT 0,
    audit_status INT NOT NULL DEFAULT 1,
    is_deleted INT NOT NULL DEFAULT 0,
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS tb_content_comment (
    comment_id BIGINT NOT NULL PRIMARY KEY,
    content_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    content VARCHAR(1000) DEFAULT NULL,
    like_count INT NOT NULL DEFAULT 0,
    audit_status INT NOT NULL DEFAULT 1,
    is_deleted INT NOT NULL DEFAULT 0,
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS tb_content_like (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    content_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_content_user (content_id, user_id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS tb_content_collect (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    content_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_content_user (content_id, user_id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS tb_answer_like (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    answer_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_answer_user (answer_id, user_id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS tb_comment_like (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    comment_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_comment_user (comment_id, user_id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS tb_user_follow (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    follow_user_id BIGINT NOT NULL,
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    is_deleted INT NOT NULL DEFAULT 0,
    UNIQUE KEY uk_user_follow_pair (user_id, follow_user_id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS tb_notification (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
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
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_type VARCHAR(64) NOT NULL,
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
    UNIQUE KEY uk_outbox_event_id (event_id),
    KEY idx_outbox_scan (status, next_retry_time, create_time),
    KEY idx_outbox_lease_recovery (status, locked_until, create_time),
    KEY idx_outbox_aggregate (aggregate_type, aggregate_id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS tb_inbox_event (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL,
    consumer_name VARCHAR(128) NOT NULL,
    outbox_event_id BIGINT DEFAULT NULL,
    event_type VARCHAR(64) DEFAULT NULL,
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
    UNIQUE KEY uk_inbox_consumer_event (consumer_name, event_id),
    KEY idx_inbox_status_lease (status, locked_until, create_time),
    KEY idx_inbox_outbox_event (outbox_event_id),
    KEY idx_inbox_aggregate (aggregate_type, aggregate_id)
) ENGINE=InnoDB;
