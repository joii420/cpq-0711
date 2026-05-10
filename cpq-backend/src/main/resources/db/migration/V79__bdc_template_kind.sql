-- V79: basic_data_config 加 template_kind 列 — 让组件 PathPickerDrawer 能按"报价/核价"过滤 sheet
--
-- 取值：
--   'QUOTATION' — 报价模板专用（只在报价模板字段配置里能看到）
--   'COSTING'   — 核价模板专用（只在核价模板字段配置里能看到）
--   'BOTH'      — 通用，两类模板都能看到（缺省值）
--
-- 历史 sheet 默认 BOTH（保守，不破坏现有报价路径）；
-- V78 注册的 11 个 v_costing_* / costing_part_* sheet 标 COSTING。

ALTER TABLE basic_data_config
    ADD COLUMN IF NOT EXISTS template_kind VARCHAR(20) NOT NULL DEFAULT 'BOTH';

ALTER TABLE basic_data_config
    DROP CONSTRAINT IF EXISTS chk_bdc_template_kind;

ALTER TABLE basic_data_config
    ADD CONSTRAINT chk_bdc_template_kind
    CHECK (template_kind IN ('QUOTATION', 'COSTING', 'BOTH'));

CREATE INDEX IF NOT EXISTS idx_bdc_template_kind
    ON basic_data_config(template_kind);

-- 把核价相关 sheet 标 COSTING（V78 注册过 11 个）
UPDATE basic_data_config
SET template_kind = 'COSTING'
WHERE target_table IN (
    'v_costing_element_price', 'v_costing_material_price', 'v_costing_exchange_rate',
    'costing_part_process_cost', 'costing_part_tooling_cost',
    'costing_part_material_bom', 'costing_part_element_bom',
    'costing_part_quality_check', 'costing_part_plating',
    'costing_part_design_cost', 'costing_part_weight'
);
