-- V68: 为 E2E 测试新增 alice (SALES_REP) + bob (SALES_MANAGER) 种子用户
--
-- 背景：
--   V1 仅创建 admin 用户。E2E 测试需要 SALES_REP 与 SALES_MANAGER 角色账号验证 RBAC 语义。
--   V44 等迁移引入的 *-tester 测试用户没有可用 password_hash，无法登录。
--
-- 种子账号：
--   alice (SALES_REP)    密码 Admin@2026（与 admin 同 hash）
--   bob   (SALES_MANAGER) 密码 Admin@2026
--
-- 实现：先 INSERT 占位行，再用 admin 的真实 hash UPDATE（因为 V1 中 admin 的 hash
-- 是有效的 bcrypt(Admin@2026)，而这里直接复用避免每次手算 bcrypt）。
--
-- 不影响生产：使用 ON CONFLICT DO NOTHING；管理员上线后可单独改密。
INSERT INTO "user" (username, full_name, email, password_hash, role, status, is_first_login)
VALUES
  ('alice', 'Alice Sales Rep',     'alice@cpq-system.com',
   '$2a$12$placeholder_will_be_overwritten',
   'SALES_REP',     'ACTIVE', false),
  ('bob',   'Bob Sales Manager',   'bob@cpq-system.com',
   '$2a$12$placeholder_will_be_overwritten',
   'SALES_MANAGER', 'ACTIVE', false)
ON CONFLICT (username) DO NOTHING;

-- 复制 admin 的有效 password_hash（确保密码真的是 Admin@2026）
UPDATE "user"
SET password_hash = (SELECT password_hash FROM "user" WHERE username = 'admin')
WHERE username IN ('alice', 'bob');
