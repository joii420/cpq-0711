-- V313__sel_template_tables.sql
-- 选配参数池 + 行业选配模板（Plan 2）。参数类型封闭(种子3行)，可选值动态取自 V6。

CREATE TABLE sel_param_type (
    code                 VARCHAR(30)  PRIMARY KEY,   -- MATERIAL / ELEMENT / PROCESS
    name                 VARCHAR(50)  NOT NULL,
    value_mode           VARCHAR(20)  NOT NULL,       -- single / multi / adjust
    data_source_key      VARCHAR(50),                 -- 取候选值的 handler key（adjust 类可空）
    persist_handler_key  VARCHAR(50),                 -- 落库 handler key（Plan 3 用）
    sort_order           INTEGER      NOT NULL DEFAULT 0
);

INSERT INTO sel_param_type (code, name, value_mode, data_source_key, persist_handler_key, sort_order) VALUES
  ('MATERIAL', '材质',   'single', 'MATERIAL_RECIPE',       'MATERIAL_RECIPE_BIND', 1),
  ('ELEMENT',  '元素含量','adjust', NULL,                    'ELEMENT_OVERRIDE',     2),
  ('PROCESS',  '工序',   'multi',  'V6_PROCESS_MASTER',     'PROCESS_LIST',         3)
ON CONFLICT (code) DO NOTHING;

CREATE TABLE sel_template (
    id            UUID PRIMARY KEY,
    industry_code VARCHAR(50) NOT NULL UNIQUE,   -- 具体行业码 / __DEFAULT__ / __GLOBAL__，一行业一套
    name          VARCHAR(100) NOT NULL,
    status        VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    version       INTEGER      NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE TABLE sel_template_item (
    id              UUID PRIMARY KEY,
    template_id     UUID NOT NULL REFERENCES sel_template(id) ON DELETE CASCADE,
    param_type_code VARCHAR(30) NOT NULL REFERENCES sel_param_type(code),
    enabled         BOOLEAN NOT NULL DEFAULT TRUE,
    sort_order      INTEGER NOT NULL DEFAULT 0,
    UNIQUE (template_id, param_type_code)
);

CREATE TABLE sel_template_item_value (
    id               UUID PRIMARY KEY,
    item_id          UUID NOT NULL REFERENCES sel_template_item(id) ON DELETE CASCADE,
    allowed_value_key VARCHAR(100) NOT NULL   -- 空集(无行)=不限；有行=只允许这些值
);
CREATE INDEX idx_sel_tiv_item ON sel_template_item_value(item_id);
