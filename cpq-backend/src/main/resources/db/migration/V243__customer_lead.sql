-- V243: 客户线索（v0.4 P1 客户身份处理 SOP）
-- 详见 docs/3D产品选配方案.md §17.5

CREATE TABLE IF NOT EXISTS customer_lead (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    lead_code       VARCHAR(40) NOT NULL UNIQUE,
    source_type     VARCHAR(32) NOT NULL,
    share_token     VARCHAR(64),
    contact_name    VARCHAR(128) NOT NULL,
    contact_phone   VARCHAR(40) NOT NULL,
    contact_email   VARCHAR(128),
    company_name    VARCHAR(255),
    note            TEXT,
    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING_REVIEW',
    reviewed_by     UUID,
    reviewed_at     TIMESTAMP WITH TIME ZONE,
    review_action   VARCHAR(32),
    bound_customer_id UUID,                                   -- 软关联 customer（避免跨模块强依赖）
    review_note     TEXT,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_lead_status CHECK (status IN ('PENDING_REVIEW','CONVERTED','REJECTED')),
    CONSTRAINT chk_lead_review_action CHECK (
        review_action IS NULL OR review_action IN ('BIND_EXISTING','CREATE_NEW','REJECT')
    )
);
CREATE INDEX IF NOT EXISTS idx_customer_lead_status ON customer_lead(status);
CREATE INDEX IF NOT EXISTS idx_customer_lead_phone ON customer_lead(contact_phone);
CREATE INDEX IF NOT EXISTS idx_customer_lead_share ON customer_lead(share_token) WHERE share_token IS NOT NULL;

CREATE SEQUENCE IF NOT EXISTS seq_customer_lead_seq START 1;
