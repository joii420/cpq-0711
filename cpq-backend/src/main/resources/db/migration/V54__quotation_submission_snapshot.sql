-- V54: v5.1 §10 提交快照机制 — quotation 表新增 submission_snapshot JSONB 列

ALTER TABLE quotation
    ADD COLUMN IF NOT EXISTS submission_snapshot JSONB;

COMMENT ON COLUMN quotation.submission_snapshot IS
    'v5.1 §10 快照机制：DRAFT→SUBMITTED 时冻结的全量数据快照
     格式：{ referencedVersions, elementActualPrices, formulaDefinitions, masterDataSnapshot, snapshotAt }';

CREATE INDEX IF NOT EXISTS idx_quotation_submission_snapshot
    ON quotation USING GIN (submission_snapshot);
