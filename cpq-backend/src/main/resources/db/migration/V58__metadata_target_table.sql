-- V58: V5 元数据化 — basic_data_config 加 target_table + target_discriminator,
--      basic_data_attribute 加 is_required
-- Ref: docs/PRD.md V5 元数据化改造 PM 决策 1-7



ALTER TABLE basic_data_config
    ADD COLUMN IF NOT EXISTS target_table          VARCHAR(64),
    ADD COLUMN IF NOT EXISTS target_discriminator  JSONB;

ALTER TABLE basic_data_attribute
    ADD COLUMN IF NOT EXISTS is_required BOOLEAN NOT NULL DEFAULT false;

CREATE INDEX IF NOT EXISTS idx_bdc_target_table
    ON basic_data_config(target_table)
    WHERE target_table IS NOT NULL;

COMMENT ON COLUMN basic_data_config.target_table IS
    'V58: 该 sheet 行写入哪张物理表（mat_part / mat_bom / mat_fee 等）。NULL = sheet 不参与导入';
COMMENT ON COLUMN basic_data_config.target_discriminator IS
    'V58: 写入物理表时附加的固定列值（如 {"bom_type":"INCOMING"} 或 {"fee_type":"INCOMING_OTHER"}）';
COMMENT ON COLUMN basic_data_attribute.is_required IS
    'V58: 该列是否必填，解析时为空抛 ValidationResult.error';

-- ── mat_fee fee_type 枚举扩展：加 3 个年降相关类型 ─────────────────────────────
-- V44 chk_mat_fee_type 原有：INCOMING_FIXED / INCOMING_OTHER / FINISHED_FIXED /
--     FINISHED_OTHER / ASSEMBLY_PROCESS
-- V58 新增：INCOMING_ANNUAL_DOWN / ASSEMBLY_ANNUAL_DOWN / ANNUAL_REDUCTION_FACTOR
ALTER TABLE mat_fee DROP CONSTRAINT IF EXISTS chk_mat_fee_type;
ALTER TABLE mat_fee ADD CONSTRAINT chk_mat_fee_type CHECK (
    fee_type IN (
        'INCOMING_FIXED',
        'INCOMING_OTHER',
        'FINISHED_FIXED',
        'FINISHED_OTHER',
        'ASSEMBLY_PROCESS',
        'INCOMING_ANNUAL_DOWN',
        'ASSEMBLY_ANNUAL_DOWN',
        'ANNUAL_REDUCTION_FACTOR'
    )
);

COMMENT ON CONSTRAINT chk_mat_fee_type ON mat_fee IS
    'V58: 扩展 fee_type 枚举，新增来料年降/组装年降/年降系数三种类型';
