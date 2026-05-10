-- ============================================================
-- V39: Restore system_config values to defaults (may have been mutated by test runs)
-- This ensures a clean baseline for CI/test environments.
-- ============================================================
UPDATE system_config SET config_value = default_value WHERE config_key = 'import.product_lock_timeout_seconds';
UPDATE system_config SET config_value = default_value WHERE config_key = 'validation.composition_tolerance';
