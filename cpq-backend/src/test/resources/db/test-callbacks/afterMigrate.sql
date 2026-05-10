-- Flyway afterMigrate callback for test environment.
-- Runs before every test JVM start (on every Flyway migrate call).
-- Resets mutable test data to a known baseline so tests are idempotent.

-- 1. Reset system_config values that tests may mutate without restoring on assertion failure.
UPDATE system_config SET config_value = default_value
WHERE config_key IN (
    'import.product_lock_timeout_seconds',
    'import.ddl_lock_timeout_seconds',
    'validation.composition_tolerance',
    'business.gross_margin_warning_min'
);

-- 2. Ensure admin user is ACTIVE so UserResourceTest.disableLastAdminFails works correctly.
--    Set all non-admin SYSTEM_ADMIN users to INACTIVE so admin is the only active one.
UPDATE "user"
SET status = 'INACTIVE'
WHERE role = 'SYSTEM_ADMIN'
  AND username != 'admin';

UPDATE "user"
SET status = 'ACTIVE',
    failed_login_attempts = 0,
    locked_until = NULL,
    full_name = '系统管理员'
WHERE username = 'admin'
  AND role = 'SYSTEM_ADMIN';
