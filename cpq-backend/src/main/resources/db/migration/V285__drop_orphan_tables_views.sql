-- V285: 删除完全无引用的孤儿表/视图（2026-06-02 审计）。
-- 判定：后端 Java / 前端 / PG 视图依赖 / FK / component_sql_view·template_sql_view 配置 全部零引用。
-- 排除：element_density（10行数据待确认）、_archived_product_data_pool_v4（V5ChainEndToEndTest 引用）。
-- 依赖关系（已核实唯一一处，均在本删除集内）：v_q_siemens_class1_costs → v_c_plating_cost_merged，
--   故 v_q_*/v_part_*/v_composite_* 先删，v_c_* 后删，表最后。

-- ── 1) v_q_* / v_part_* / v_composite_* 视图（依赖者，先删）──
DROP VIEW IF EXISTS v_composite_child_weights;
DROP VIEW IF EXISTS v_part_element_price;
DROP VIEW IF EXISTS v_part_material_price;
DROP VIEW IF EXISTS v_part_plating_scheme;
DROP VIEW IF EXISTS v_q_assembly_merged;
DROP VIEW IF EXISTS v_q_component_fee_merged;
DROP VIEW IF EXISTS v_q_component_merged;
DROP VIEW IF EXISTS v_q_element_merged;
DROP VIEW IF EXISTS v_q_finished_merged;
DROP VIEW IF EXISTS v_q_incoming_merged;
DROP VIEW IF EXISTS v_q_part_plating_scheme;
DROP VIEW IF EXISTS v_q_plating_merged;
DROP VIEW IF EXISTS v_q_rockwell_costs;
DROP VIEW IF EXISTS v_q_siemens_class1_costs;

-- ── 2) v_c_* 视图（被依赖，后删）──
DROP VIEW IF EXISTS v_c_consumable_prod_merged;
DROP VIEW IF EXISTS v_c_depreciation_merged;
DROP VIEW IF EXISTS v_c_energy_aux_merged;
DROP VIEW IF EXISTS v_c_energy_prod_merged;
DROP VIEW IF EXISTS v_c_finished_fixed_fee_merged;
DROP VIEW IF EXISTS v_c_finished_other_merged;
DROP VIEW IF EXISTS v_c_finished_proc_merged;
DROP VIEW IF EXISTS v_c_incoming_fixed_fee_merged;
DROP VIEW IF EXISTS v_c_incoming_other_merged;
DROP VIEW IF EXISTS v_c_incoming_proc_merged;
DROP VIEW IF EXISTS v_c_labor_cost_merged;
DROP VIEW IF EXISTS v_c_outsource_merged;
DROP VIEW IF EXISTS v_c_packaging_merged;
DROP VIEW IF EXISTS v_c_part_mapping_merged;
DROP VIEW IF EXISTS v_c_plating_cost_merged;
DROP VIEW IF EXISTS v_c_plating_scheme_merged;
DROP VIEW IF EXISTS v_c_raw_bom_merged;
DROP VIEW IF EXISTS v_c_raw_bom_priced;
DROP VIEW IF EXISTS v_c_raw_element_bom_merged;
DROP VIEW IF EXISTS v_c_tooling_merged;
DROP VIEW IF EXISTS v_c_weight_merged;

-- ── 3) 遗留/备份/测试表 ──
DROP TABLE IF EXISTS costing_template_legacy_backup;
DROP TABLE IF EXISTS mat_mapping_cpn_history;
DROP TABLE IF EXISTS mat_part_version_log_pre_v177;
DROP TABLE IF EXISTS product_config_price_rule;
DROP TABLE IF EXISTS test_item_no;
