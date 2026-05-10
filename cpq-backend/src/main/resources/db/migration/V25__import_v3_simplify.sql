-- V25: Excel Import v3 — simplify ImportRecord, remove CustomerExcelTemplate/ImportMappingTemplate FKs

-- ImportRecord: add template_id, config_snapshot; make old FKs nullable
ALTER TABLE import_record ADD COLUMN IF NOT EXISTS template_id UUID REFERENCES template(id);
ALTER TABLE import_record ADD COLUMN IF NOT EXISTS config_snapshot JSONB;

-- Make old FK columns nullable (keep data, stop requiring them)
ALTER TABLE import_record ALTER COLUMN excel_template_id DROP NOT NULL;
ALTER TABLE import_record ALTER COLUMN mapping_template_id DROP NOT NULL;

-- Note: We don't DROP customer_excel_template or import_mapping_template tables
-- They remain in the DB but are no longer actively used by v3 code.
-- Existing data is preserved for backward compatibility.
