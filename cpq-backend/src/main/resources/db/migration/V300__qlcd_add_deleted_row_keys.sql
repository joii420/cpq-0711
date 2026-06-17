-- driver 默认行可永久删除：每页签墓碑数组 [{effKey, fp}]
ALTER TABLE quotation_line_component_data
    ADD COLUMN IF NOT EXISTS deleted_row_keys jsonb NOT NULL DEFAULT '[]'::jsonb;
