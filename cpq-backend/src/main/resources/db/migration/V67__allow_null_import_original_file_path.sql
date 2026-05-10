-- ============================================================
-- V67: Allow original_file_path and mapping_snapshot in import_record to be NULL.
-- Required by QIMP-RETENTION-19 data retention policy:
-- after the 12-month retention period, the physical file is deleted
-- and the path column is set to NULL while keeping the import_record row.
-- Also relaxes mapping_snapshot NOT NULL (simplification for v5 import flow).
-- Also extends import_status CHECK to include COMPLETED (used by legacy records).
-- ============================================================
ALTER TABLE import_record ALTER COLUMN original_file_path DROP NOT NULL;
ALTER TABLE import_record ALTER COLUMN mapping_snapshot DROP NOT NULL;

ALTER TABLE import_record DROP CONSTRAINT IF EXISTS chk_ir_status;
ALTER TABLE import_record ADD CONSTRAINT chk_ir_status
    CHECK (import_status IN ('SUCCESS', 'PARTIAL', 'FAILED', 'COMPLETED'));
