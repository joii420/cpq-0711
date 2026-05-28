-- ============================================================
-- V223: 扩展 import_record.import_status CHECK 约束
-- 加入 PROCESSING（V6 导入开始时的初始状态）
-- 原约束: SUCCESS / PARTIAL / FAILED / COMPLETED
-- 新约束: + PROCESSING
-- ============================================================

ALTER TABLE import_record DROP CONSTRAINT IF EXISTS chk_ir_status;

ALTER TABLE import_record ADD CONSTRAINT chk_ir_status
    CHECK (import_status IN ('SUCCESS', 'PARTIAL', 'FAILED', 'COMPLETED', 'PROCESSING'));
