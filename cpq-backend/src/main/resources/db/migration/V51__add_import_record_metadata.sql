ALTER TABLE import_record ADD COLUMN IF NOT EXISTS metadata JSONB;
COMMENT ON COLUMN import_record.metadata IS 'v1: UI-1/UI-2 决策的 resolutions[] JSON';
CREATE INDEX IF NOT EXISTS idx_import_record_metadata_gin ON import_record USING GIN (metadata);
