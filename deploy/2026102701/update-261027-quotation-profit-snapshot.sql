-- CPQ database increment: V454 -> V455
-- Source migration: V455__task261026_quotation_line_profit_snapshot.sql
-- Generated: 2026-10-27
-- Purpose: freeze the sales-product profit value on newly created quotation lines.

BEGIN;

ALTER TABLE quotation_line_item
    ADD COLUMN IF NOT EXISTS profit_snapshot numeric(26,12) NULL;

UPDATE flyway_schema_history
SET version = '455',
    script = '<< Flyway Baseline >>'
WHERE installed_rank = 1;

COMMIT;

-- Self-check 1, expected: 1
SELECT count(*) AS profit_snapshot_column_expected_1
FROM information_schema.columns
WHERE table_schema = 'public'
  AND table_name = 'quotation_line_item'
  AND column_name = 'profit_snapshot'
  AND data_type = 'numeric'
  AND numeric_precision = 26
  AND numeric_scale = 12
  AND is_nullable = 'YES';

-- Self-check 2, expected: 0
SELECT count(*) AS profit_snapshot_default_expected_0
FROM information_schema.columns
WHERE table_schema = 'public'
  AND table_name = 'quotation_line_item'
  AND column_name = 'profit_snapshot'
  AND column_default IS NOT NULL;

-- Self-check 3, expected immediately after upgrading a V454 database: 0
SELECT count(*) AS historical_profit_snapshot_non_null_expected_0
FROM quotation_line_item
WHERE profit_snapshot IS NOT NULL;

-- Self-check 4, expected: 455
SELECT version AS flyway_baseline_expected_455
FROM flyway_schema_history
WHERE installed_rank = 1;
