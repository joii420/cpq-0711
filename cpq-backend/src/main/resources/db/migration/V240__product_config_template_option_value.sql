-- V240: 选配模板 + 选项 + 选项值 三表
-- 详见 docs/3D产品选配方案.md §7.2-7.4
-- 2026-05-26 立

CREATE TABLE IF NOT EXISTS product_config_template (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code                VARCHAR(64) NOT NULL UNIQUE,
    name                VARCHAR(128) NOT NULL,
    category            VARCHAR(80),
    base_part_no        VARCHAR(64),                          -- 基础料号（未来加 mat_part FK）
    base_model_id       UUID,                                  -- v0.4 P0: 关联 mat_part_model (V244 加)
    base_model_version  INTEGER,
    base_model_snapshot_at TIMESTAMP WITH TIME ZONE,
    description         TEXT,
    show_price          BOOLEAN NOT NULL DEFAULT TRUE,
    metadata            JSONB NOT NULL DEFAULT '{}',
    status              VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
    version             INTEGER NOT NULL DEFAULT 1,
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    created_by          UUID,
    updated_by          UUID,
    CONSTRAINT chk_pct_status CHECK (status IN ('DRAFT','PUBLISHED','ARCHIVED'))
);
CREATE INDEX IF NOT EXISTS idx_pct_status ON product_config_template(status);
CREATE INDEX IF NOT EXISTS idx_pct_category ON product_config_template(category);
CREATE INDEX IF NOT EXISTS idx_pct_base_model ON product_config_template(base_model_id) WHERE base_model_id IS NOT NULL;

CREATE TABLE IF NOT EXISTS product_config_option (
    id                          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    template_id                 UUID NOT NULL REFERENCES product_config_template(id) ON DELETE CASCADE,
    code                        VARCHAR(64) NOT NULL,
    label                       VARCHAR(128) NOT NULL,
    option_type                 VARCHAR(32) NOT NULL,
    data_type                   VARCHAR(20),
    assign_mode                 VARCHAR(20),
    is_required                 BOOLEAN NOT NULL DEFAULT TRUE,
    default_value               VARCHAR(128),
    min_value                   VARCHAR(40),
    max_value                   VARCHAR(40),
    partno_prefix               VARCHAR(20),
    partno_suffix               VARCHAR(20),
    sort_order                  INTEGER NOT NULL DEFAULT 0,
    description                 TEXT,
    metadata                    JSONB DEFAULT '{}',
    -- §18A.4 追溯特征库
    source_feature_field_id     BIGINT,                       -- 不强制 FK，特征库可独立删除
    source_feature_snapshot_at  TIMESTAMP WITH TIME ZONE,
    created_at                  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at                  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    UNIQUE(template_id, code),
    CONSTRAINT chk_pco_option_type CHECK (option_type IN ('EXCLUSIVE','MULTI_SELECT','NUMERIC','TEXT','COLOR'))
);
CREATE INDEX IF NOT EXISTS idx_pco_template ON product_config_option(template_id);
CREATE INDEX IF NOT EXISTS idx_pco_src_field ON product_config_option(source_feature_field_id) WHERE source_feature_field_id IS NOT NULL;

CREATE TABLE IF NOT EXISTS product_config_option_value (
    id                          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    option_id                   UUID NOT NULL REFERENCES product_config_option(id) ON DELETE CASCADE,
    code                        VARCHAR(64) NOT NULL,
    label                       VARCHAR(128) NOT NULL,
    description                 TEXT,
    price_delta                 NUMERIC(18,4) NOT NULL DEFAULT 0,
    sort_order                  INTEGER NOT NULL DEFAULT 0,
    partno_include              BOOLEAN NOT NULL DEFAULT TRUE,
    is_active                   BOOLEAN NOT NULL DEFAULT TRUE,
    -- v0.4 特征语义吸收（§4.3 选项树即特征树）
    feature_type                VARCHAR(40),
    attributes                  JSONB,
    tags                        TEXT[],
    geometry_ref                JSONB,
    sub_model_part_no           VARCHAR(64),
    attach_mode                 VARCHAR(20),
    attach_position             JSONB,
    replace_base_mesh           BOOLEAN DEFAULT FALSE,
    -- §18A.4 追溯
    source_feature_value_id     BIGINT,
    source_feature_snapshot_at  TIMESTAMP WITH TIME ZONE,
    local_only                  BOOLEAN NOT NULL DEFAULT FALSE,
    created_at                  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at                  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    UNIQUE(option_id, code)
);
CREATE INDEX IF NOT EXISTS idx_pcov_option ON product_config_option_value(option_id);
CREATE INDEX IF NOT EXISTS idx_pcov_active ON product_config_option_value(is_active);
CREATE INDEX IF NOT EXISTS idx_pcov_src_value ON product_config_option_value(source_feature_value_id) WHERE source_feature_value_id IS NOT NULL;
