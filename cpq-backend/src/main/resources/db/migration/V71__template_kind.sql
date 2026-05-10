-- V71: 把 template 改成"双类型表"——加 template_kind 字段区分报价模板 / 核价模板
--
-- 设计：
--   QUOTATION  — 报价模板（按客户专属 / 通用兜底维度，customer_id 可有）
--   COSTING    — 核价模板（默认所有客户可用，customer_id 留空 = 通用）
--
-- 现有所有行回填为 QUOTATION，保留既有报价模板语义，不影响在用报价单。
-- 后续 / 前端在 Excel 模板配置页读 costing_template 表的同时，也在模板配置页看到带类型列的统一视图。

ALTER TABLE template
    ADD COLUMN IF NOT EXISTS template_kind VARCHAR(20) NOT NULL DEFAULT 'QUOTATION';

UPDATE template SET template_kind = 'QUOTATION' WHERE template_kind IS NULL;

ALTER TABLE template DROP CONSTRAINT IF EXISTS chk_template_kind;
ALTER TABLE template
    ADD CONSTRAINT chk_template_kind
    CHECK (template_kind IN ('QUOTATION', 'COSTING'));

CREATE INDEX IF NOT EXISTS idx_template_kind ON template(template_kind);
