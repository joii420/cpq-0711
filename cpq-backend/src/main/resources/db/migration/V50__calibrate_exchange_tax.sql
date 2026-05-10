-- V50: 校准 exchange_rate / customer_tax 与 v5.1 §3.2 公式契约对齐
-- 见 RECORD TODO（commit b703064）和路线 X X.7 收尾遗留项。

-- ============== exchange_rate ==============
-- §3.2 EXCHANGE(amount, from, to, date?) 签名无 customer_id；
-- 改为可空，支持全局汇率 + 客户级覆盖两种语义并存。
ALTER TABLE exchange_rate
    ALTER COLUMN customer_id DROP NOT NULL;

-- 旧唯一索引 uq_exchange_rate_curr 仅锁定 ACTIVE 行，阻止历史多版本共存——
-- 报价快照需要历史汇率回溯，必须保留所有 effective_date 的历史行。
DROP INDEX IF EXISTS uq_exchange_rate_curr;

-- 新唯一索引：按 (customer_id_norm, from, to, effective_date) 全维度唯一。
-- COALESCE 把 NULL customer_id 标准化为零 UUID，从而 NULL 行也参与唯一性约束。
CREATE UNIQUE INDEX uq_exchange_rate_full
    ON exchange_rate(
        COALESCE(customer_id, '00000000-0000-0000-0000-000000000000'::uuid),
        from_currency,
        to_currency,
        effective_date
    );

COMMENT ON COLUMN exchange_rate.customer_id IS
    'NULL 表示全局汇率；非 NULL 表示该客户的协议汇率。EXCHANGE 公式优先匹配客户级。';

-- ============== customer_tax ==============
-- §3.2 TAX_INCLUDED(price, customer_id) / TAX_EXCLUDED(price, customer_id) 无 tax_type 入参；
-- 同客户在公式视角下仅一种当前生效税率。
ALTER TABLE customer_tax
    DROP COLUMN IF EXISTS tax_type;

DROP INDEX IF EXISTS uq_customer_tax_curr;
DROP INDEX IF EXISTS idx_customer_tax_cust;

-- 新索引：按 (customer_id, effective_date) 全维度唯一，支持历史税率回溯。
CREATE UNIQUE INDEX uq_customer_tax_eff
    ON customer_tax(customer_id, effective_date);

CREATE INDEX idx_customer_tax_cust
    ON customer_tax(customer_id, effective_date DESC);

COMMENT ON COLUMN customer_tax.tax_rate IS
    '客户当前生效税率。is_current=true 表示当前生效；历史行 is_current=false 仅供报价快照回溯。';
