-- V49: archive v4 ProductDataPool table (route X stage X.5)
-- Renames product_data_pool → _archived_product_data_pool_v4
-- Keeps row history; new schema (V44 14 tables) takes over the role.
ALTER TABLE IF EXISTS product_data_pool
    RENAME TO _archived_product_data_pool_v4;
COMMENT ON TABLE _archived_product_data_pool_v4 IS
    'Archived from V29; superseded by 14 v5.1 physical tables (V44). Safe to DROP after one release cycle.';
