-- V104: 全局变量注册表 (P1 核心闭环)
--
-- 给公式引擎暴露 3 张"价格表"作为命名全局变量:
--   ELEM_PRICE      → v_costing_element_price.costing_price (按 element_code 查)
--   MAT_PRICE       → v_costing_material_price.costing_price (按 material_no 查)
--   EXCHANGE_RATE   → v_costing_exchange_rate.costing_rate (按 from_currency+to_currency 查)
--
-- 公式 token 形态 (前端组装, 后端解析):
--   { type:'global_variable', code:'ELEM_PRICE',
--     key_values: {element_code:'Cu'} }                     -- 静态 key
--   { type:'global_variable', code:'ELEM_PRICE',
--     key_field_refs: {element_code:'电镀元素'} }            -- 动态 key (取行字段)
--
-- 后端编译产物: BNF path 字符串, 复用 path resolver 流水线
--   例: v_costing_element_price[element_code='Cu'].costing_price
--
-- 决策依据 (用户已确认):
--   #1 同时支持 LOOKUP_TABLE / SCALAR (var_type 列预留 SCALAR)
--   #2 同时支持静态 key / 动态 key_field_refs
--   #8 复用 costing_price_version (3 张视图都已是 PUBLISHED+is_default 过滤)

CREATE TABLE IF NOT EXISTS global_variable_definition (
    code            VARCHAR(64)  PRIMARY KEY,
    name            VARCHAR(100) NOT NULL,
    var_type        VARCHAR(20)  NOT NULL DEFAULT 'LOOKUP_TABLE',  -- LOOKUP_TABLE | SCALAR
    source_view     VARCHAR(100) NOT NULL,                          -- 物理视图/表名
    key_columns     JSONB        NOT NULL DEFAULT '[]'::jsonb,      -- ["element_code"] 或 ["from_currency","to_currency"]
    value_column    VARCHAR(100) NOT NULL,                          -- 取值列
    label_template  VARCHAR(200),                                   -- UI 列出可选 key 时的显示模板; NULL → 用 key 拼接
    unit            VARCHAR(20),                                    -- CNY/KG, %, -
    description     TEXT,
    sort_order      INT          DEFAULT 0,
    is_active       BOOLEAN      NOT NULL DEFAULT true,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_gvd_var_type CHECK (var_type IN ('LOOKUP_TABLE','SCALAR'))
);

COMMENT ON TABLE global_variable_definition IS
'V104: 全局变量注册表; 公式引擎按 code 查 def, 编译 token 为 BNF path 调 resolver';

COMMENT ON COLUMN global_variable_definition.key_columns IS
'JSON 数组. 单键: ["element_code"]; 复合键: ["from_currency","to_currency"]';

COMMENT ON COLUMN global_variable_definition.label_template IS
'UI 候选 key 列表的显示文案. 简单写列名即按列拼接; 留空时默认按 key_columns 拼接';

-- ============================================================
-- 种子: 3 个全局变量
-- ============================================================
INSERT INTO global_variable_definition
    (code, name, var_type, source_view, key_columns, value_column, label_template, unit, description, sort_order)
VALUES
    ('ELEM_PRICE', '元素核价价格', 'LOOKUP_TABLE',
     'v_costing_element_price',
     '["element_code"]'::jsonb,
     'costing_price',
     'element_code',
     'CNY/KG',
     '元素核价价格表 (按元素代码查), 来源 costing_element_price 当前发布版本',
     10),
    ('MAT_PRICE', '材料核价价格', 'LOOKUP_TABLE',
     'v_costing_material_price',
     '["material_no"]'::jsonb,
     'costing_price',
     'material_no',
     'CNY/KG',
     '材料核价价格表 (按料号查), 来源 costing_material_price 当前发布版本',
     20),
    ('EXCHANGE_RATE', '核价汇率', 'LOOKUP_TABLE',
     'v_costing_exchange_rate',
     '["from_currency","to_currency"]'::jsonb,
     'costing_rate',
     'from_currency:to_currency',
     '-',
     '汇率管理表 (按源币种→目标币种查), 来源 costing_exchange_rate 当前发布版本',
     30)
ON CONFLICT (code) DO UPDATE SET
    name           = EXCLUDED.name,
    source_view    = EXCLUDED.source_view,
    key_columns    = EXCLUDED.key_columns,
    value_column   = EXCLUDED.value_column,
    label_template = EXCLUDED.label_template,
    unit           = EXCLUDED.unit,
    description    = EXCLUDED.description,
    sort_order     = EXCLUDED.sort_order,
    updated_at     = now();
