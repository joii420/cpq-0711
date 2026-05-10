-- V32: 安全网 — 确保系统管理员账号总是可登录
--
-- 背景: 测试报告 T1 反馈 v4 迁移后 admin 偶发为 INACTIVE 状态导致无法登录。
-- 虽然 V1 种子是 ACTIVE,但开发/测试环境可能因脚本误操作或并发测试被改为 INACTIVE。
-- 此迁移仅在 admin 账号存在时执行,幂等。

UPDATE "user"
SET    status = 'ACTIVE',
       failed_login_attempts = 0,
       locked_until = NULL
WHERE  username = 'admin'
  AND  role = 'SYSTEM_ADMIN'
  AND  status <> 'ACTIVE';
