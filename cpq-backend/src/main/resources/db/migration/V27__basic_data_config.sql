-- V27: 基础数据配置 — Sheet 配置 + 列属性 + 衍生字段

-- ========== BasicDataConfig: 基础数据 Sheet 配置 ==========
CREATE TABLE IF NOT EXISTS basic_data_config (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    sheet_name             VARCHAR(200) NOT NULL,
    sheet_index            INTEGER      NOT NULL DEFAULT 0,
    header_row_index       INTEGER      NOT NULL DEFAULT 1,
    data_start_row_index   INTEGER      NOT NULL DEFAULT 2,
    description            TEXT,
    parent_config_id       UUID REFERENCES basic_data_config(id),
    join_columns           JSONB        NOT NULL DEFAULT '[]'::jsonb,
    sort_order             INTEGER      NOT NULL DEFAULT 0,
    status                 VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    created_at             TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at             TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_bdc_parent ON basic_data_config(parent_config_id);
CREATE UNIQUE INDEX IF NOT EXISTS uq_bdc_sheet_name ON basic_data_config(sheet_name) WHERE status = 'ACTIVE';

-- ========== BasicDataAttribute: 基础数据列属性 ==========
CREATE TABLE IF NOT EXISTS basic_data_attribute (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    config_id       UUID NOT NULL REFERENCES basic_data_config(id) ON DELETE CASCADE,
    column_letter   VARCHAR(10)  NOT NULL,
    column_title    VARCHAR(200) NOT NULL,
    variable_code   VARCHAR(100) NOT NULL UNIQUE,
    variable_label  VARCHAR(200) NOT NULL,
    data_type       VARCHAR(20)  NOT NULL DEFAULT 'VALUE',  -- IDENTIFIER / VALUE
    status          VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    sort_order      INTEGER      NOT NULL DEFAULT 0,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_bda_config ON basic_data_attribute(config_id);
CREATE INDEX IF NOT EXISTS idx_bda_status ON basic_data_attribute(status);

-- ========== DerivedAttribute: 衍生字段 ==========
CREATE TABLE IF NOT EXISTS derived_attribute (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    host_sheet_id      UUID NOT NULL REFERENCES basic_data_config(id) ON DELETE CASCADE,
    variable_code      VARCHAR(100) NOT NULL UNIQUE,
    variable_label     VARCHAR(200) NOT NULL,
    data_type          VARCHAR(20)  NOT NULL DEFAULT 'VALUE',
    computation_type   VARCHAR(30)  NOT NULL,  -- LOOKUP / EXPRESSION / AGGREGATE
    computation        JSONB        NOT NULL,
    status             VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    sort_order         INTEGER      NOT NULL DEFAULT 0,
    created_at         TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_da_host_sheet ON derived_attribute(host_sheet_id);
CREATE INDEX IF NOT EXISTS idx_da_status ON derived_attribute(status);
