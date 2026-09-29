-- Independent currency master and exchange-rate history. The legacy exchange_rate_v6 table is not reused.
CREATE TABLE IF NOT EXISTS currency (
    code VARCHAR(10) PRIMARY KEY, name VARCHAR(100) NOT NULL, symbol VARCHAR(16), enabled BOOLEAN NOT NULL DEFAULT TRUE,
    is_base BOOLEAN NOT NULL DEFAULT FALSE, sort_order INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_currency_base_code CHECK ((is_base = FALSE) OR code = 'CNY')
);
INSERT INTO currency(code,name,symbol,enabled,is_base,sort_order) VALUES ('CNY','人民币','￥',TRUE,TRUE,0)
ON CONFLICT (code) DO UPDATE SET is_base=TRUE, enabled=TRUE;
CREATE TABLE IF NOT EXISTS currency_exchange_rate (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(), currency_code VARCHAR(10) NOT NULL REFERENCES currency(code),
    rate NUMERIC(26,12) NOT NULL, effective_date DATE NOT NULL, source VARCHAR(200), status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), created_by UUID,
    CONSTRAINT ck_currency_rate_positive CHECK (rate > 0), CONSTRAINT ck_currency_rate_status CHECK (status IN ('ACTIVE','INACTIVE'))
);
CREATE INDEX IF NOT EXISTS idx_currency_rate_lookup ON currency_exchange_rate(currency_code,effective_date DESC,updated_at DESC,id DESC);
ALTER TABLE customer ADD COLUMN IF NOT EXISTS default_currency VARCHAR(10) NOT NULL DEFAULT 'CNY';
UPDATE customer SET default_currency='CNY' WHERE default_currency IS NULL;
ALTER TABLE customer DROP CONSTRAINT IF EXISTS fk_customer_default_currency;
ALTER TABLE customer ADD CONSTRAINT fk_customer_default_currency FOREIGN KEY(default_currency) REFERENCES currency(code);
CREATE TABLE IF NOT EXISTS customer_exchange_rate_strategy (
    customer_id UUID PRIMARY KEY REFERENCES customer(id) ON DELETE CASCADE, method VARCHAR(32) NOT NULL,
    window_num INTEGER, window_unit VARCHAR(16), specific_date DATE, day_of_month INTEGER,
    version INTEGER NOT NULL DEFAULT 1, updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), updated_by UUID,
    CONSTRAINT ck_exchange_strategy_method CHECK (method IN ('LATEST','AVG','MAX','MIN','SPECIFIC_DATE','PREVIOUS_MONTH_DAY','MONTH_DAY','PREVIOUS_MONTH_END')),
    CONSTRAINT ck_exchange_strategy_day CHECK (day_of_month IS NULL OR (day_of_month BETWEEN 1 AND 31)),
    CONSTRAINT ck_exchange_strategy_window CHECK (window_num IS NULL OR window_num > 0)
);
CREATE TABLE IF NOT EXISTS customer_exchange_rate_strategy_history (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(), customer_id UUID NOT NULL REFERENCES customer(id), method VARCHAR(32) NOT NULL,
    window_num INTEGER, window_unit VARCHAR(16), specific_date DATE, day_of_month INTEGER, version INTEGER NOT NULL,
    changed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), changed_by UUID
);
ALTER TABLE quotation ADD COLUMN IF NOT EXISTS currency VARCHAR(10) NOT NULL DEFAULT 'CNY';
ALTER TABLE quotation ADD COLUMN IF NOT EXISTS exchange_rate NUMERIC(26,12) NOT NULL DEFAULT 1;
ALTER TABLE quotation ADD COLUMN IF NOT EXISTS exchange_rate_date DATE;
ALTER TABLE quotation ADD COLUMN IF NOT EXISTS exchange_rate_source VARCHAR(200);
ALTER TABLE quotation ADD COLUMN IF NOT EXISTS exchange_rate_captured_at TIMESTAMPTZ;
ALTER TABLE quotation ADD COLUMN IF NOT EXISTS exchange_rate_strategy_snapshot JSONB;
ALTER TABLE quotation DROP CONSTRAINT IF EXISTS fk_quotation_currency;
ALTER TABLE quotation ADD CONSTRAINT fk_quotation_currency FOREIGN KEY(currency) REFERENCES currency(code);
ALTER TABLE element_daily_price ADD COLUMN IF NOT EXISTS source_price NUMERIC(26,12);
ALTER TABLE element_daily_price ADD COLUMN IF NOT EXISTS source_currency VARCHAR(10);
ALTER TABLE element_daily_price ADD COLUMN IF NOT EXISTS source_rate NUMERIC(26,12);
ALTER TABLE element_daily_price ADD COLUMN IF NOT EXISTS cny_price NUMERIC(26,12);
