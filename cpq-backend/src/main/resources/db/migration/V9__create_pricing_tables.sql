-- ===========================================
-- V9: Pricing strategy and rule tables
-- Used by Discount Calculation Engine (M4a)
-- ===========================================

CREATE TABLE pricing_strategy (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    customer_id UUID NOT NULL REFERENCES customer(id),
    name VARCHAR(200) NOT NULL,
    type VARCHAR(20) NOT NULL DEFAULT 'DISCOUNT',
    base_discount DECIMAL(5,2) NOT NULL DEFAULT 100,
    min_order_amount DECIMAL(18,4) NOT NULL DEFAULT 0,
    effective_date DATE,
    expiration_date DATE,
    priority INTEGER NOT NULL DEFAULT 1,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT chk_ps_status CHECK (status IN ('ACTIVE','EXPIRED','DISABLED'))
);

CREATE TABLE pricing_rule (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    strategy_id UUID NOT NULL REFERENCES pricing_strategy(id) ON DELETE CASCADE,
    rule_type VARCHAR(30) NOT NULL DEFAULT 'BULK_DISCOUNT',
    threshold_amount DECIMAL(18,4) NOT NULL,
    discount_rate DECIMAL(5,2) NOT NULL,
    sort_order INTEGER DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

CREATE INDEX idx_ps_customer ON pricing_strategy(customer_id);
CREATE INDEX idx_pr_strategy ON pricing_rule(strategy_id);
