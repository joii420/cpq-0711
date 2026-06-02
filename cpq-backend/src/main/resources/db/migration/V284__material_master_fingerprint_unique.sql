-- V284: 全局指纹去重约束迁到 V6（替代 V44 uq_mat_part_fingerprint）。
-- partial：仅对非 NULL config_fingerprint 唯一（导入的 V6 料号 fingerprint 为 NULL，不受约束）。
CREATE UNIQUE INDEX IF NOT EXISTS uq_material_master_fingerprint
    ON material_master (config_fingerprint)
    WHERE config_fingerprint IS NOT NULL;
