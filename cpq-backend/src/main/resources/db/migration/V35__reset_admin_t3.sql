-- V35: 重置 admin（T3 测试环境恢复）
UPDATE "user"
SET    status = 'ACTIVE',
       failed_login_attempts = 0,
       locked_until = NULL
WHERE  username = 'admin';
