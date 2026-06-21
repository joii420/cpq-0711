-- V302: Step3 行级折扣字段落库（原 QuotationStep3 注释声称已有实为缺失，此处补全）
ALTER TABLE quotation_line_item
    ADD COLUMN IF NOT EXISTS annual_volume        INTEGER,
    ADD COLUMN IF NOT EXISTS discount_source      VARCHAR(64),
    ADD COLUMN IF NOT EXISTS discount_base_amount NUMERIC(18,4),
    ADD COLUMN IF NOT EXISTS discount_rate_applied NUMERIC(5,2),
    ADD COLUMN IF NOT EXISTS line_discount_amount NUMERIC(18,4),
    ADD COLUMN IF NOT EXISTS line_unit_price      NUMERIC(18,4),
    ADD COLUMN IF NOT EXISTS line_final_price     NUMERIC(18,4),
    ADD COLUMN IF NOT EXISTS line_total_amount    NUMERIC(18,4),
    ADD COLUMN IF NOT EXISTS discount_rule_code   VARCHAR(64);
