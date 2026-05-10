-- V48: Revert V47 (modifiable_by data hack) - return to V37 baseline
UPDATE system_config
SET modifiable_by = 'SYSTEM_ADMIN'
WHERE config_key = 'import.product_lock_timeout_seconds';
