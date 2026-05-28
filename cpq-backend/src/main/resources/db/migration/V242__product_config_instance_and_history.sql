-- V242: 选配实例 + 变更历史
-- 详见 docs/3D产品选配方案.md §7.8 + §13.2
-- 2026-05-26 立

-- 实例编号序列（CI-{yyyyMM}-{seq:04d}，按月归 1，后端生成时核对）
CREATE SEQUENCE IF NOT EXISTS seq_config_instance_seq START 1;

CREATE TABLE IF NOT EXISTS product_config_instance (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    instance_code           VARCHAR(40) NOT NULL UNIQUE,
    template_id             UUID NOT NULL REFERENCES product_config_template(id),
    template_version        INTEGER,
    name                    VARCHAR(128),
    customer_id             UUID,                              -- 软关联（不强制 FK 避免循环依赖）
    customer_lead_id        UUID,                              -- v0.4 P1: 客户自助提交时填
    user_id                 UUID,
    share_token             VARCHAR(64),
    selected_values         JSONB NOT NULL DEFAULT '{}',
    config_fingerprint      VARCHAR(64),
    computed_total_price    NUMERIC(18,4),
    base_price              NUMERIC(18,4),
    status                  VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
    -- 弱关联报价单（双向独立）
    linked_quotation_id     UUID,
    linked_at               TIMESTAMP WITH TIME ZONE,
    linked_by               UUID,
    -- 首次提交时填，后续不变
    generated_part_no       VARCHAR(64),
    generated_quotation_id  UUID,
    generated_line_item_id  UUID,
    expires_at              TIMESTAMP WITH TIME ZONE,
    created_at              TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_pci_status CHECK (status IN ('DRAFT','SUBMITTED','LINKED','EXPIRED'))
);
CREATE INDEX IF NOT EXISTS idx_pci_template ON product_config_instance(template_id);
CREATE INDEX IF NOT EXISTS idx_pci_customer ON product_config_instance(customer_id, status) WHERE customer_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_pci_status ON product_config_instance(status);
CREATE INDEX IF NOT EXISTS idx_pci_fingerprint ON product_config_instance(config_fingerprint);
CREATE INDEX IF NOT EXISTS idx_pci_share ON product_config_instance(share_token) WHERE share_token IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_pci_linked_quotation ON product_config_instance(linked_quotation_id) WHERE linked_quotation_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_pci_user ON product_config_instance(user_id, status) WHERE user_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_pci_expires ON product_config_instance(expires_at) WHERE expires_at IS NOT NULL;

CREATE TABLE IF NOT EXISTS product_config_instance_history (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    instance_id     UUID NOT NULL REFERENCES product_config_instance(id) ON DELETE CASCADE,
    action          VARCHAR(32) NOT NULL,
    actor_user_id   UUID,
    before_snapshot JSONB,
    after_snapshot  JSONB,
    note            TEXT,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_pcih_instance ON product_config_instance_history(instance_id);
