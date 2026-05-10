-- V33: 重置 admin 账号到可登录状态
-- 用于本地/开发环境恢复测试态。生产环境此 SQL 等价于无操作（如果 admin 已是 ACTIVE 且未锁）。

UPDATE "user"
SET    status = 'ACTIVE',
       failed_login_attempts = 0,
       locked_until = NULL
WHERE  username = 'admin';
