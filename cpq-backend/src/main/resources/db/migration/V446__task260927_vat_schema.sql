-- task-260927 VAT tax categories, effective versions and quotation snapshots.
-- This migration is additive only: existing products/quotes remain NULL and are
-- deliberately not backfilled.

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

-- A category can have one and only one effective version for any date.
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

-- Seed only when absent; never overwrite an existing administrator choice.
INSERT INTO tax_category (code, name, status, is_default)
SELECT 'GENERAL_GOODS', '一般货物', 'ACTIVE', NOT EXISTS (SELECT 1 FROM tax_category WHERE is_default = TRUE)
ON CONFLICT (code) DO NOTHING;
-- Preserve an administrator-selected default; otherwise make the seeded category the default.
UPDATE tax_category SET is_default = TRUE
 WHERE code = 'GENERAL_GOODS'
   AND NOT EXISTS (SELECT 1 FROM tax_category WHERE is_default = TRUE);
INSERT INTO tax_category_vat_version (tax_category_id, vat_rate, effective_from, status)
SELECT id, 13.00, CURRENT_DATE, 'ACTIVE'
  FROM tax_category c
 WHERE c.code = 'GENERAL_GOODS'
   AND NOT EXISTS (
       SELECT 1 FROM tax_category_vat_version v
        WHERE v.tax_category_id = c.id AND v.effective_from = CURRENT_DATE
   );

COMMENT ON TABLE tax_category IS 'VAT tax classification; stable identity used by products and quotation snapshots';
COMMENT ON TABLE tax_category_vat_version IS 'Effective-dated VAT versions; quotation rows store immutable snapshots';
