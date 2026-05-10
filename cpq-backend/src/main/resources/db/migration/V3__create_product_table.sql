CREATE TABLE product (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(200) NOT NULL,
    sku VARCHAR(100) NOT NULL UNIQUE,
    category VARCHAR(30) NOT NULL,
    specification VARCHAR(500),
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    tags JSONB DEFAULT '[]',
    external_id VARCHAR(200),
    last_synced_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT chk_product_category CHECK (category IN ('STANDARD','CUSTOM','RAW_MATERIAL')),
    CONSTRAINT chk_product_status CHECK (status IN ('ACTIVE','INACTIVE'))
);
CREATE INDEX idx_product_category ON product(category);
CREATE INDEX idx_product_status ON product(status);
CREATE INDEX idx_product_sku ON product(sku);
