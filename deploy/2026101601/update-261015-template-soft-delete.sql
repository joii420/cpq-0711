-- Source migration: V451__task261015_template_soft_delete.sql
-- Generated: 2026-10-15
-- Enable the DELETED tombstone for published product-card templates.
-- Idempotent for an already-upgraded database.

BEGIN;

ALTER TABLE template
    DROP CONSTRAINT IF EXISTS chk_template_status;

ALTER TABLE template
    ADD CONSTRAINT chk_template_status
    CHECK (status IN ('DRAFT', 'PUBLISHED', 'ARCHIVED', 'DELETED'));

-- Keep the manual-deployment baseline aligned with the application migration.
UPDATE flyway_schema_history
   SET version = '451',
       script = '<< Flyway Baseline >>'
 WHERE installed_rank = 1
   AND version ~ '^[0-9]+$'
   AND version::integer < 451;

-- Self-check: status constraint must admit DELETED.
SELECT conname,
       pg_get_constraintdef(oid) AS constraint_definition
  FROM pg_constraint
 WHERE conrelid = 'public.template'::regclass
   AND conname = 'chk_template_status';

COMMIT;
