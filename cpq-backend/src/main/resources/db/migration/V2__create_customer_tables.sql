-- ===========================================
-- V2: Customer management tables
-- Customer, CustomerContact
-- ===========================================

-- Customer
CREATE TABLE customer (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(200) NOT NULL,
    code VARCHAR(50) NOT NULL UNIQUE,
    level VARCHAR(20) NOT NULL DEFAULT 'STANDARD',
    industry VARCHAR(100),
    region VARCHAR(100),
    address TEXT,
    accumulated_amount DECIMAL(18,4) NOT NULL DEFAULT 0,
    credit_limit DECIMAL(18,4),
    payment_method VARCHAR(100),
    remarks TEXT,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    version INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT chk_customer_level CHECK (level IN ('DIAMOND','VIP','GOLD','SILVER','STANDARD')),
    CONSTRAINT chk_customer_status CHECK (status IN ('ACTIVE','INACTIVE'))
);

CREATE INDEX idx_customer_level ON customer(level);
CREATE INDEX idx_customer_status ON customer(status);
CREATE INDEX idx_customer_name ON customer(name);

-- Customer code sequence
CREATE SEQUENCE customer_code_seq START 1;

-- CustomerContact
CREATE TABLE customer_contact (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    customer_id UUID NOT NULL REFERENCES customer(id),
    name VARCHAR(200) NOT NULL,
    role VARCHAR(50),
    phone VARCHAR(20) NOT NULL,
    email VARCHAR(200),
    wechat VARCHAR(100),
    is_primary BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

CREATE INDEX idx_contact_customer ON customer_contact(customer_id);
