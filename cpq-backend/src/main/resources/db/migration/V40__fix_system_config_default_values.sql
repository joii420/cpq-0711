-- ============================================================
-- V40: Reset system_config values to defaults where tests may have left dirty state
-- Ensures test runs start from a known baseline.
-- ============================================================
UPDATE system_config SET config_value = default_value
WHERE config_key IN (
    'import.product_lock_timeout_seconds',
    'validation.composition_tolerance',
    'business.gross_margin_warning_min'
);
