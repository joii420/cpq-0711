-- V34: 解锁 admin（T2 runtime 测试触发锁定后恢复）
-- 与 V32/V33 配合,确保 dev 环境总能登录 admin 进行回归。

UPDATE "user"
SET    failed_login_attempts = 0,
       locked_until = NULL,
       status = 'ACTIVE'
WHERE  username = 'admin';
