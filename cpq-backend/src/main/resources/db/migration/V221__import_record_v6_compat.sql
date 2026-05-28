-- ============================================================
-- V221: import_record 表适配 V6 基础数据导入
-- 1) customer_id 改 nullable（核价导入无客户上下文，customer_no 来自 Excel 行）
-- 2) 加 system_type 列区分 QUOTE / PRICING / OTHER
--    sheet_summary 复用现有 metadata JSONB 列存放（不另起列）
-- ============================================================

ALTER TABLE import_record ALTER COLUMN customer_id DROP NOT NULL;

ALTER TABLE import_record
    ADD COLUMN IF NOT EXISTS system_type VARCHAR(20);

COMMENT ON COLUMN import_record.system_type IS 'V6 导入区分：QUOTE 报价基础数据 / PRICING 核价基础数据 / OTHER 老链路';

-- 老数据回填：customer_id 不为空 → QUOTE（V5 报价导入），否则 OTHER
UPDATE import_record SET system_type = CASE
    WHEN system_type IS NOT NULL THEN system_type
    WHEN customer_id IS NOT NULL THEN 'QUOTE'
    ELSE 'OTHER'
END WHERE system_type IS NULL;
