-- V245: 选项值业务实体引用（§18A v0.4 收敛 — 替代废弃的 mat_feature_reference）
-- 2026-05-27 立

CREATE TABLE IF NOT EXISTS product_config_value_reference (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    option_value_id UUID NOT NULL REFERENCES product_config_option_value(id) ON DELETE CASCADE,
    ref_type        VARCHAR(32) NOT NULL,           -- MATERIAL / PROCESS / COMPONENT / COST_ITEM / GLOBAL_VAR
    ref_code        VARCHAR(80) NOT NULL,            -- 业务实体编码（如 MAT-AGCU-85-15）
    qty             VARCHAR(40),                     -- 数量（字符串以支持公式）
    unit            VARCHAR(20),                     -- 单位
    note            TEXT,
    metadata        JSONB DEFAULT '{}',
    sort_order      INTEGER NOT NULL DEFAULT 0,
    is_active       BOOLEAN NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    created_by      UUID,
    CONSTRAINT chk_pcvr_ref_type CHECK (ref_type IN (
        'MATERIAL', 'PROCESS', 'COMPONENT', 'COST_ITEM', 'GLOBAL_VAR'
    ))
);
CREATE INDEX IF NOT EXISTS idx_pcvr_value ON product_config_value_reference(option_value_id);
CREATE INDEX IF NOT EXISTS idx_pcvr_type ON product_config_value_reference(ref_type);
CREATE INDEX IF NOT EXISTS idx_pcvr_code ON product_config_value_reference(ref_code);
