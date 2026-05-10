-- V72: quotation 加列 costing_card_template_id —— 引用 template 表的核价模板（template_kind='COSTING'）
--
-- 背景：
--   V71 已经把 template 表加了 template_kind 字段区分 QUOTATION / COSTING；
--   原先「创建报价单」抽屉里的"核价模板"接的是 costing_template 表（Excel 列结构），
--   跟用户在「模板配置」里新建的核价模板（template 表里 template_kind=COSTING）是两个独立体系。
--
-- 本次：
--   - quotation.customer_template_id  → 报价模板（已有）
--   - quotation.costing_card_template_id → 核价模板（新加，本字段）
--   - costing_sheet.costing_template_id → Excel 视图列结构（与本字段并行存在，不冲突）
--
-- FK：引用 template(id)；CHECK 不在迁移层做（前端选择时已限定 template_kind=COSTING）。

ALTER TABLE quotation
    ADD COLUMN IF NOT EXISTS costing_card_template_id UUID;

ALTER TABLE quotation
    DROP CONSTRAINT IF EXISTS quotation_costing_card_template_fk;

ALTER TABLE quotation
    ADD CONSTRAINT quotation_costing_card_template_fk
    FOREIGN KEY (costing_card_template_id) REFERENCES template(id) ON DELETE SET NULL;

CREATE INDEX IF NOT EXISTS idx_quotation_costing_card_template
    ON quotation(costing_card_template_id);
