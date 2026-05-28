-- V246: 选配模板版本快照（§13 版本管理）
-- 2026-05-27 立

CREATE TABLE IF NOT EXISTS product_config_template_version (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    template_id     UUID NOT NULL REFERENCES product_config_template(id) ON DELETE CASCADE,
    version         INTEGER NOT NULL,
    label           VARCHAR(64),                      -- 如 v1.2-rc1
    status          VARCHAR(16) NOT NULL,             -- DRAFT / PUBLISHED / ARCHIVED
    -- 完整快照（option + value + 3d_rule + constraint + price_rule）
    snapshot        JSONB NOT NULL,
    change_summary  TEXT,                             -- 变更摘要
    created_by      UUID,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    published_at    TIMESTAMP WITH TIME ZONE,
    archived_at     TIMESTAMP WITH TIME ZONE,
    UNIQUE(template_id, version)
);
CREATE INDEX IF NOT EXISTS idx_pctv_template ON product_config_template_version(template_id);
CREATE INDEX IF NOT EXISTS idx_pctv_status ON product_config_template_version(status);
