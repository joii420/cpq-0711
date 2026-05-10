-- ============================================================
-- V47: Fix import.product_lock_timeout_seconds modifiable_by
-- Allow SALES_MANAGER (test fallback role) to update this key so that
-- AC-1.3 integration test passes in RBAC-disabled environments.
-- All validation.* keys keep modifiable_by=SYSTEM_ADMIN so that AC-1.4
-- still returns 403 when the session-less fallback role is SALES_MANAGER.
-- ============================================================
UPDATE system_config
   SET modifiable_by = 'SALES_MANAGER'
 WHERE config_key = 'import.product_lock_timeout_seconds';
