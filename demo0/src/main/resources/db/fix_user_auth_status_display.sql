-- 修复历史数据：tb_user_auth 已通过但 tb_user.auth_status 误写为 1（审核中）
-- 约定：tb_user.auth_status 2=已认证；tb_user_auth.audit_status 1=已通过
SET NAMES utf8mb4;

UPDATE tb_user u
    INNER JOIN tb_user_auth ua ON u.id = ua.user_id AND ua.audit_status = 1
SET u.auth_status = 2,
    u.update_time = NOW()
WHERE u.auth_status IS NULL OR u.auth_status <> 2;

UPDATE tb_user u
    INNER JOIN tb_user_auth ua ON u.id = ua.user_id AND ua.audit_status = 2
SET u.auth_status = 3,
    u.update_time = NOW()
WHERE u.auth_status IS NULL OR u.auth_status NOT IN (3);
