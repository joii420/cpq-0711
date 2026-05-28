-- V239: 特征库（方案 B 快照复制主数据）
-- 详见 docs/3D产品选配方案.md §18A
-- 2026-05-26 立

CREATE TABLE IF NOT EXISTS cpq_feature_group (
    id                BIGSERIAL PRIMARY KEY,
    code              VARCHAR(40)  NOT NULL UNIQUE,
    name              VARCHAR(255) NOT NULL,
    description       TEXT,
    category          VARCHAR(80),
    status            VARCHAR(20)  NOT NULL DEFAULT 'DRAFT',
    erp_ref_code      VARCHAR(40),
    extra_attrs       JSONB,
    created_by        VARCHAR(64),
    created_at        TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_by        VARCHAR(64),
    updated_at        TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_cpq_fg_status CHECK (status IN ('DRAFT','ACTIVE','ARCHIVED'))
);
CREATE INDEX IF NOT EXISTS idx_cpq_fg_status ON cpq_feature_group(status);
CREATE INDEX IF NOT EXISTS idx_cpq_fg_category ON cpq_feature_group(category);

CREATE TABLE IF NOT EXISTS cpq_feature_field (
    id                BIGSERIAL PRIMARY KEY,
    group_id          BIGINT NOT NULL REFERENCES cpq_feature_group(id) ON DELETE CASCADE,
    code              VARCHAR(40) NOT NULL,
    name              VARCHAR(255) NOT NULL,
    sort_order        INTEGER NOT NULL DEFAULT 0,
    data_type         VARCHAR(20) NOT NULL,
    assign_mode       VARCHAR(20) NOT NULL,
    is_required       BOOLEAN NOT NULL DEFAULT FALSE,
    default_value     VARCHAR(255),
    min_value         VARCHAR(40),
    max_value         VARCHAR(40),
    code_length       INTEGER,
    decimal_places    INTEGER,
    data_source_ref   VARCHAR(80),
    partno_prefix     VARCHAR(20),
    partno_suffix     VARCHAR(20),
    extra_attrs       JSONB,
    created_at        TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMP NOT NULL DEFAULT NOW(),
    UNIQUE(group_id, code),
    CONSTRAINT chk_cpq_ff_data_type CHECK (data_type IN ('STRING','NUMBER','DATE','BOOLEAN')),
    CONSTRAINT chk_cpq_ff_assign_mode CHECK (assign_mode IN ('MANUAL','SELECT','COMPUTED'))
);
CREATE INDEX IF NOT EXISTS idx_cpq_ff_group ON cpq_feature_field(group_id);

CREATE TABLE IF NOT EXISTS cpq_feature_value (
    id                BIGSERIAL PRIMARY KEY,
    field_id          BIGINT NOT NULL REFERENCES cpq_feature_field(id) ON DELETE CASCADE,
    code              VARCHAR(40) NOT NULL,
    label             VARCHAR(255) NOT NULL,
    description       TEXT,
    sort_order        INTEGER NOT NULL DEFAULT 0,
    partno_include    BOOLEAN NOT NULL DEFAULT TRUE,
    is_active         BOOLEAN NOT NULL DEFAULT TRUE,
    extra_attrs       JSONB,
    created_at        TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMP NOT NULL DEFAULT NOW(),
    UNIQUE(field_id, code)
);
CREATE INDEX IF NOT EXISTS idx_cpq_fv_field ON cpq_feature_value(field_id);
CREATE INDEX IF NOT EXISTS idx_cpq_fv_active ON cpq_feature_value(is_active);
