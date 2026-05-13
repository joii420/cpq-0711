-- V165__composite_process_def_and_mat.sql

CREATE TABLE IF NOT EXISTS composite_process_def (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    code            VARCHAR(64)  NOT NULL UNIQUE,
    name            VARCHAR(128) NOT NULL,
    icon            VARCHAR(8),
    description     TEXT,
    param_schema    JSONB        NOT NULL DEFAULT '[]',
    sort_order      INT          NOT NULL DEFAULT 0,
    status          VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_composite_process_def_status CHECK (status IN ('ACTIVE','INACTIVE'))
);
CREATE INDEX IF NOT EXISTS idx_composite_process_def_status ON composite_process_def(status, sort_order);

CREATE TABLE IF NOT EXISTS mat_composite_process (
    id                    UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    parent_hf_part_no     VARCHAR(64)  NOT NULL,
    def_code              VARCHAR(64)  NOT NULL REFERENCES composite_process_def(code),
    seq_no                INT          NOT NULL,
    participating_parts   JSONB        NOT NULL,
    param_values          JSONB        NOT NULL DEFAULT '{}',
    part_version          INT          NOT NULL DEFAULT 2000,
    is_current            BOOLEAN      NOT NULL DEFAULT true,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by            UUID,
    CONSTRAINT uq_mat_composite_process UNIQUE (parent_hf_part_no, seq_no, part_version)
);
CREATE INDEX IF NOT EXISTS idx_mat_composite_process_parent ON mat_composite_process(parent_hf_part_no, part_version);

COMMENT ON TABLE composite_process_def IS '组合工艺字典(铆接/焊接/钎焊等)';
COMMENT ON TABLE mat_composite_process IS '组合工艺实例(挂在父料号上)';
