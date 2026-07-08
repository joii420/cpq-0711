-- V314__sel_part_signature.sql —— 销售侧客户维度指纹去重登记(独立于 material_master.config_fingerprint 生产侧全局)
CREATE TABLE sel_part_signature (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    customer_no           VARCHAR(50)  NOT NULL,
    structure_version     VARCHAR(10)  NOT NULL,
    config_fingerprint    CHAR(64)     NOT NULL,
    config_signature_text TEXT         NOT NULL,
    quote_part_no         VARCHAR(32)  NOT NULL,
    product_type          VARCHAR(16)  NOT NULL DEFAULT 'SIMPLE',
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_sel_part_signature UNIQUE (customer_no, structure_version, config_fingerprint)
);
CREATE INDEX idx_sel_part_signature_quote ON sel_part_signature(quote_part_no);
