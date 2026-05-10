-- ============================================================
-- V38: Relax chk_config_key_format to allow digits in config_key
-- Fix: config_key pattern was too strict (only [a-z_]), updated to allow [a-z0-9_]
-- ============================================================
ALTER TABLE system_config
    DROP CONSTRAINT IF EXISTS chk_config_key_format;

ALTER TABLE system_config
    ADD CONSTRAINT chk_config_key_format
        CHECK (config_key ~ '^[a-z0-9_]+\.[a-z0-9_]+$');
