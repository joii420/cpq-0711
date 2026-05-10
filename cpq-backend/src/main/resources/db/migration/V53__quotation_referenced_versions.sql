-- ============================================================
-- V53: quotation 表新增 referenced_versions JSONB 字段
-- Ref: docs/superpowers/specs/2026-04-26-cpq-design-v5.1.md §6.6 DRAFT 漂移检测
-- ============================================================

ALTER TABLE quotation
    ADD COLUMN IF NOT EXISTS referenced_versions JSONB;

COMMENT ON COLUMN quotation.referenced_versions IS
    'v5.1 §6.6 漂移检测：DRAFT 报价单引用的基础数据版本快照
     格式：{"mat_process":{"<hfPartNo>|<customerId>":<version>,...},
             "mat_fee":{...}, "plating_fee":{...}, "element_price":{...}}
     Key 为"业务键"（hf_part_no|customer_id），Value 为该业务键当时 is_current=true 行的 version。
     仅 DRAFT 状态下填写；SUBMITTED 后冻结（submit 时不清除，但 refresh-versions 仅限 DRAFT）。';

CREATE INDEX IF NOT EXISTS idx_quotation_referenced_versions
    ON quotation USING GIN (referenced_versions);
