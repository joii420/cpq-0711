-- V57: relax basic_data_attribute.variable_code from global-unique to (config_id, variable_code) composite unique
-- Allows multiple sheets to share common variable names (e.g. HF_PART_NO appears in incoming-BOM/element-BOM/fee sheets)
-- Also adjusts derived_attribute symmetrically (V27 defined both tables with global-unique variable_code)

-- basic_data_attribute
ALTER TABLE basic_data_attribute DROP CONSTRAINT IF EXISTS basic_data_attribute_variable_code_key;
DO $$ BEGIN
    ALTER TABLE basic_data_attribute
        ADD CONSTRAINT uq_bda_config_var UNIQUE (config_id, variable_code);
EXCEPTION WHEN duplicate_object OR duplicate_table THEN NULL; END $$;

-- derived_attribute (V27 uses host_sheet_id, not config_id -- use correct column name)
ALTER TABLE derived_attribute DROP CONSTRAINT IF EXISTS derived_attribute_variable_code_key;
DO $$ BEGIN
    ALTER TABLE derived_attribute
        ADD CONSTRAINT uq_da_host_var UNIQUE (host_sheet_id, variable_code);
EXCEPTION WHEN duplicate_object OR duplicate_table THEN NULL; END $$;

COMMENT ON CONSTRAINT uq_bda_config_var ON basic_data_attribute IS
    'V57: variable_code unique within same config, can be reused across configs (e.g. HF_PART_NO)';
COMMENT ON CONSTRAINT uq_da_host_var ON derived_attribute IS
    'V57: variable_code unique within same host_sheet, can be reused across sheets';
