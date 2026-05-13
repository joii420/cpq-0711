-- V173__register_process_default_price_variable.sql
-- 1. 创建 process_default_cost 表 (工序默认单价)
-- 2. 注册到 basic_data_config 让 BNF 可引用
-- 3. 注册全局变量 PROCESS_DEFAULT_PRICE (已存在则跳过)
-- 4. Seed 9 行 p1-p9 默认单价

-- ── Step 1: 创建工序默认单价表 ─────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS process_default_cost (
    process_code  VARCHAR(64)    PRIMARY KEY,
    unit_price    DECIMAL(12,4)  NOT NULL,
    currency      VARCHAR(8)     NOT NULL DEFAULT 'CNY',
    updated_at    TIMESTAMPTZ    NOT NULL DEFAULT NOW()
);
COMMENT ON TABLE process_default_cost IS '工序默认单价表 — 选配工序 mat_process.unit_price=NULL 时模板从此表按 process_code 取价';
COMMENT ON COLUMN process_default_cost.process_code IS '工序代码, 与 mat_process.process_code 关联';
COMMENT ON COLUMN process_default_cost.unit_price   IS '默认单价 (CNY/单位)';
COMMENT ON COLUMN process_default_cost.currency     IS '货币代码, 默认 CNY';
COMMENT ON COLUMN process_default_cost.updated_at   IS '最后更新时间';

-- ── Step 2: 注册到 basic_data_config (无 sheet_name 唯一约束, 用 NOT EXISTS 守卫) ──
-- actual columns: id(uuid,gen_random_uuid()), sheet_name, sheet_index, header_row_index,
--   data_start_row_index, description, parent_config_id, join_columns,
--   sort_order, status, created_at, updated_at, target_table,
--   target_discriminator, template_kind
INSERT INTO basic_data_config (
    sheet_name,
    sheet_index,
    header_row_index,
    data_start_row_index,
    description,
    join_columns,
    sort_order,
    status,
    template_kind
)
SELECT
    'process_default_cost',
    0,
    1,
    2,
    '工序默认单价 — BNF 变量 PROCESS_DEFAULT_PRICE 的数据源',
    '[]'::jsonb,
    0,
    'ACTIVE',
    'BOTH'
WHERE NOT EXISTS (
    SELECT 1 FROM basic_data_config WHERE sheet_name = 'process_default_cost'
);

-- ── Step 3: 注册全局变量 PROCESS_DEFAULT_PRICE (已存在则跳过) ───────────────────
-- actual columns: code(PK), name, var_type, source_view, key_columns,
--   value_column, label_template, unit, description, sort_order,
--   is_active, created_at, updated_at
INSERT INTO global_variable_definition (
    code,
    name,
    var_type,
    source_view,
    key_columns,
    value_column,
    description,
    sort_order,
    is_active
)
VALUES (
    'PROCESS_DEFAULT_PRICE',
    '工序默认单价',
    'LOOKUP_TABLE',
    'process_default_cost',
    '["process_code"]'::jsonb,
    'unit_price',
    '按工序代码查询默认单价, 用于 mat_process.unit_price 为 NULL 时的回退取价',
    100,
    true
)
ON CONFLICT (code) DO NOTHING;

-- ── Step 4: Seed 9 行工序默认单价 p1-p9 ────────────────────────────────────────
INSERT INTO process_default_cost (process_code, unit_price) VALUES
    ('p1', 0.5000),
    ('p2', 1.2000),
    ('p3', 0.8000),
    ('p4', 1.0000),
    ('p5', 0.3000),
    ('p6', 1.5000),
    ('p7', 0.9000),
    ('p8', 0.4000),
    ('p9', 0.2000)
ON CONFLICT (process_code) DO NOTHING;
