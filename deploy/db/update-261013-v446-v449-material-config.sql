-- =====================================================================================
-- CPQ 内网数据库增量升级脚本
--
-- 源迁移版本  : V446 / V447 / V449
-- 升级区间    : Flyway 基线 V445 -> V449
-- 生成日期    : 2026-10-13
-- 适用前置    : deploy/db/cpq-init-empty.sql（V439）及已有 V440~V445 增量脚本
-- 执行环境    : Navicat / PostgreSQL 16，整份文件一次性执行
--
-- 本次包含：
--   V446 税务分类、税率版本及报价快照字段
--   V447 币种、汇率及报价汇率快照字段
--   V449 材质含量配置 group_no（Excel 组号只用于文件内分组）
--
-- 幂等性：DDL 使用 IF NOT EXISTS / IF EXISTS；种子数据使用冲突保护；可重复执行。
-- 注意：V446/V447 虽早于本次材质导入功能，但当前 V445 基线缺少对应增量，故在本文件一并补齐。
-- =====================================================================================

SET search_path = public;


-- =====================================================================================
-- 第 1 节 · V446 —— 税务分类、税率版本及报价快照
-- =====================================================================================

CREATE TABLE IF NOT EXISTS tax_category (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code VARCHAR(64) NOT NULL,
    name VARCHAR(128) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    is_default BOOLEAN NOT NULL DEFAULT FALSE,
    description TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID,
    updated_by UUID,
    CONSTRAINT uq_tax_category_code UNIQUE (code),
    CONSTRAINT chk_tax_category_status CHECK (status IN ('ACTIVE', 'INACTIVE'))
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_tax_category_default
    ON tax_category (is_default) WHERE is_default;

CREATE TABLE IF NOT EXISTS tax_category_vat_version (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tax_category_id UUID NOT NULL REFERENCES tax_category(id),
    vat_rate NUMERIC(7,4) NOT NULL,
    effective_from DATE NOT NULL,
    effective_to DATE,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID,
    updated_by UUID,
    CONSTRAINT chk_tax_category_vat_rate CHECK (vat_rate >= 0 AND vat_rate <= 100),
    CONSTRAINT chk_tax_category_vat_period CHECK (effective_to IS NULL OR effective_from < effective_to),
    CONSTRAINT chk_tax_category_vat_status CHECK (status IN ('ACTIVE', 'INACTIVE'))
);

CREATE INDEX IF NOT EXISTS idx_tax_category_vat_category_period
    ON tax_category_vat_version (tax_category_id, effective_from DESC);

CREATE EXTENSION IF NOT EXISTS btree_gist;
ALTER TABLE tax_category_vat_version
    DROP CONSTRAINT IF EXISTS ex_tax_category_vat_period;
ALTER TABLE tax_category_vat_version
    ADD CONSTRAINT ex_tax_category_vat_period
    EXCLUDE USING gist (
        tax_category_id WITH =,
        daterange(effective_from, COALESCE(effective_to, 'infinity'::date), '[)') WITH &&
    ) WHERE (status = 'ACTIVE');

ALTER TABLE ds_quote_material
    ADD COLUMN IF NOT EXISTS tax_category_id UUID REFERENCES tax_category(id);
CREATE INDEX IF NOT EXISTS idx_ds_quote_material_tax_category
    ON ds_quote_material (tax_category_id);

ALTER TABLE customer
    ADD COLUMN IF NOT EXISTS default_vat_rate NUMERIC(7,4);
ALTER TABLE customer
    DROP CONSTRAINT IF EXISTS chk_customer_default_vat_rate;
ALTER TABLE customer
    ADD CONSTRAINT chk_customer_default_vat_rate
    CHECK (default_vat_rate IS NULL OR (default_vat_rate >= 0 AND default_vat_rate <= 100));

ALTER TABLE quotation
    ADD COLUMN IF NOT EXISTS default_vat_rate NUMERIC(7,4),
    ADD COLUMN IF NOT EXISTS default_vat_source VARCHAR(32);
ALTER TABLE quotation
    DROP CONSTRAINT IF EXISTS chk_quotation_default_vat_rate;
ALTER TABLE quotation
    ADD CONSTRAINT chk_quotation_default_vat_rate
    CHECK (default_vat_rate IS NULL OR (default_vat_rate >= 0 AND default_vat_rate <= 100));

ALTER TABLE quotation_line_item
    ADD COLUMN IF NOT EXISTS tax_category_id UUID REFERENCES tax_category(id),
    ADD COLUMN IF NOT EXISTS tax_category_version_id UUID REFERENCES tax_category_vat_version(id),
    ADD COLUMN IF NOT EXISTS vat_rate NUMERIC(7,4),
    ADD COLUMN IF NOT EXISTS vat_rate_ratio NUMERIC(12,8),
    ADD COLUMN IF NOT EXISTS vat_source VARCHAR(32),
    ADD COLUMN IF NOT EXISTS vat_effective_from DATE,
    ADD COLUMN IF NOT EXISTS vat_resolved_at TIMESTAMPTZ;
ALTER TABLE quotation_line_item
    DROP CONSTRAINT IF EXISTS chk_quotation_line_vat_rate;
ALTER TABLE quotation_line_item
    ADD CONSTRAINT chk_quotation_line_vat_rate
    CHECK (vat_rate IS NULL OR (vat_rate >= 0 AND vat_rate <= 100));
CREATE INDEX IF NOT EXISTS idx_quotation_line_item_tax_category
    ON quotation_line_item (tax_category_id, tax_category_version_id);
CREATE INDEX IF NOT EXISTS idx_quotation_line_item_tax_category_version
    ON quotation_line_item (tax_category_version_id);

INSERT INTO tax_category (code, name, status, is_default)
SELECT 'GENERAL_GOODS', '一般货物', 'ACTIVE', NOT EXISTS (SELECT 1 FROM tax_category WHERE is_default = TRUE)
ON CONFLICT (code) DO NOTHING;
UPDATE tax_category SET is_default = TRUE
 WHERE code = 'GENERAL_GOODS'
   AND NOT EXISTS (SELECT 1 FROM tax_category WHERE is_default = TRUE);
INSERT INTO tax_category_vat_version (tax_category_id, vat_rate, effective_from, status)
SELECT id, 13.00, CURRENT_DATE, 'ACTIVE'
  FROM tax_category c
 WHERE c.code = 'GENERAL_GOODS'
   AND NOT EXISTS (
       SELECT 1 FROM tax_category_vat_version v
        WHERE v.tax_category_id = c.id
          AND v.status = 'ACTIVE'
          AND v.effective_to IS NULL
   );

COMMENT ON TABLE tax_category IS 'VAT tax classification; stable identity used by products and quotation snapshots';
COMMENT ON TABLE tax_category_vat_version IS 'Effective-dated VAT versions; quotation rows store immutable snapshots';


-- =====================================================================================
-- 第 2 节 · V447 —— 币种、汇率及报价汇率快照
-- =====================================================================================

CREATE TABLE IF NOT EXISTS currency (
    code VARCHAR(10) PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    symbol VARCHAR(16),
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    is_base BOOLEAN NOT NULL DEFAULT FALSE,
    sort_order INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_currency_base_code CHECK ((is_base = FALSE) OR code = 'CNY')
);
INSERT INTO currency(code,name,symbol,enabled,is_base,sort_order)
VALUES ('CNY','人民币','￥',TRUE,TRUE,0)
ON CONFLICT (code) DO UPDATE SET is_base=TRUE, enabled=TRUE;

CREATE TABLE IF NOT EXISTS currency_exchange_rate (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    currency_code VARCHAR(10) NOT NULL REFERENCES currency(code),
    rate NUMERIC(26,12) NOT NULL,
    effective_date DATE NOT NULL,
    source VARCHAR(200),
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by UUID,
    CONSTRAINT ck_currency_rate_positive CHECK (rate > 0),
    CONSTRAINT ck_currency_rate_status CHECK (status IN ('ACTIVE','INACTIVE'))
);
CREATE INDEX IF NOT EXISTS idx_currency_rate_lookup
    ON currency_exchange_rate(currency_code,effective_date DESC,updated_at DESC,id DESC);

ALTER TABLE customer ADD COLUMN IF NOT EXISTS default_currency VARCHAR(10) NOT NULL DEFAULT 'CNY';
UPDATE customer SET default_currency='CNY' WHERE default_currency IS NULL;
ALTER TABLE customer DROP CONSTRAINT IF EXISTS fk_customer_default_currency;
ALTER TABLE customer ADD CONSTRAINT fk_customer_default_currency FOREIGN KEY(default_currency) REFERENCES currency(code);

CREATE TABLE IF NOT EXISTS customer_exchange_rate_strategy (
    customer_id UUID PRIMARY KEY REFERENCES customer(id) ON DELETE CASCADE,
    method VARCHAR(32) NOT NULL,
    window_num INTEGER,
    window_unit VARCHAR(16),
    specific_date DATE,
    day_of_month INTEGER,
    version INTEGER NOT NULL DEFAULT 1,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_by UUID,
    CONSTRAINT ck_exchange_strategy_method CHECK (method IN ('LATEST','AVG','MAX','MIN','SPECIFIC_DATE','PREVIOUS_MONTH_DAY','MONTH_DAY','PREVIOUS_MONTH_END')),
    CONSTRAINT ck_exchange_strategy_day CHECK (day_of_month IS NULL OR (day_of_month BETWEEN 1 AND 31)),
    CONSTRAINT ck_exchange_strategy_window CHECK (window_num IS NULL OR window_num > 0)
);

CREATE TABLE IF NOT EXISTS customer_exchange_rate_strategy_history (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    customer_id UUID NOT NULL REFERENCES customer(id),
    method VARCHAR(32) NOT NULL,
    window_num INTEGER,
    window_unit VARCHAR(16),
    specific_date DATE,
    day_of_month INTEGER,
    version INTEGER NOT NULL,
    changed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    changed_by UUID
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


-- =====================================================================================
-- 第 3 节 · V449 —— 材质含量配置数据库组号
--   Excel 组号只在文件内声明同组；入库组号按材质现有最大值顺序生成。
-- =====================================================================================

ALTER TABLE material_recipe_config
    ADD COLUMN IF NOT EXISTS group_no INTEGER;

UPDATE material_recipe_config
   SET group_no = seq
 WHERE group_no IS NULL;

ALTER TABLE material_recipe_config
    ALTER COLUMN group_no SET NOT NULL;
ALTER TABLE material_recipe_config
    DROP CONSTRAINT IF EXISTS uq_mrc_recipe_group_no;
ALTER TABLE material_recipe_config
    ADD CONSTRAINT uq_mrc_recipe_group_no UNIQUE (recipe_id, group_no);

COMMENT ON COLUMN material_recipe_config.group_no IS
  'task-261012：材质历史配置组号，含 INACTIVE，编号不回收；Excel 组号不落库';


-- =====================================================================================
-- 第 4 节 · Flyway 基线上调 V445 -> V449
-- =====================================================================================

UPDATE flyway_schema_history
   SET version = '449', script = '<< Flyway Baseline >>'
 WHERE installed_rank = 1;


-- =====================================================================================
-- 第 5 节 · 自检（只读）
-- =====================================================================================

SELECT version AS flyway_baseline_expect_449
  FROM flyway_schema_history
 WHERE installed_rank = 1;

SELECT to_regclass('public.tax_category') AS tax_category_expect_present,
       to_regclass('public.tax_category_vat_version') AS tax_version_expect_present,
       to_regclass('public.currency') AS currency_expect_present,
       to_regclass('public.currency_exchange_rate') AS currency_rate_expect_present;

SELECT COUNT(*) AS material_config_rows,
       COUNT(group_no) AS group_no_nonnull,
       COUNT(*) FILTER (WHERE group_no = seq) AS group_no_backfilled
  FROM material_recipe_config;
