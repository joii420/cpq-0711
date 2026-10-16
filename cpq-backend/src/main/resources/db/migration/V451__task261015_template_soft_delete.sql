-- task-261015: published product-card templates use a soft-delete tombstone.
-- Existing rows and all quotation/binding/snapshot references remain untouched.
ALTER TABLE template DROP CONSTRAINT IF EXISTS chk_template_status;
ALTER TABLE template
  ADD CONSTRAINT chk_template_status
  CHECK (status IN ('DRAFT','PUBLISHED','ARCHIVED','DELETED'));
