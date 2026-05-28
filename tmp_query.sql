WITH wanted(name) AS (VALUES
  ('v_c_consumable_prod_merged'),('v_c_depreciation_merged'),('v_c_energy_aux_merged'),
  ('v_c_energy_prod_merged'),('v_c_finished_fixed_fee_merged'),('v_c_finished_other_merged'),
  ('v_c_finished_proc_merged'),('v_c_incoming_fixed_fee_merged'),('v_c_incoming_other_merged'),
  ('v_c_incoming_proc_merged'),('v_c_labor_cost_merged'),('v_c_outsource_merged'),
  ('v_c_packaging_merged'),('v_c_part_mapping_merged'),('v_c_plating_cost_merged'),
  ('v_c_plating_scheme_merged'),('v_c_raw_bom_merged'),('v_c_raw_bom_priced'),
  ('v_c_raw_element_bom_merged'),('v_c_summary_agg'),('v_c_tooling_merged'),('v_c_weight_merged'),
  ('v_composite_child_elements'),('v_composite_child_materials'),
  ('v_composite_child_processes'),('v_composite_child_weights')
)
SELECT w.name,
       CASE WHEN v.table_name IS NOT NULL THEN 'OK' ELSE 'MISSING' END AS status
  FROM wanted w
  LEFT JOIN information_schema.views v
    ON v.table_schema = 'public' AND v.table_name = w.name
 ORDER BY status DESC, w.name;

\echo '---- 视图间依赖 (view->depends_on_view) ----'
SELECT v.table_name AS view_name,
       d.table_name AS depends_on
  FROM information_schema.view_table_usage d
  JOIN information_schema.views v
    ON v.table_schema = d.view_schema AND v.table_name = d.view_name
  JOIN information_schema.views v2
    ON v2.table_schema = d.table_schema AND v2.table_name = d.table_name
 WHERE v.table_schema = 'public'
   AND v.table_name IN (
     'v_q_assembly_merged','v_q_component_fee_merged','v_q_component_merged','v_q_element_merged',
     'v_q_finished_merged','v_q_incoming_merged','v_q_part_info_merged','v_q_plating_merged',
     'v_c_consumable_prod_merged','v_c_depreciation_merged','v_c_energy_aux_merged',
     'v_c_energy_prod_merged','v_c_finished_fixed_fee_merged','v_c_finished_other_merged',
     'v_c_finished_proc_merged','v_c_incoming_fixed_fee_merged','v_c_incoming_other_merged',
     'v_c_incoming_proc_merged','v_c_labor_cost_merged','v_c_outsource_merged',
     'v_c_packaging_merged','v_c_part_mapping_merged','v_c_plating_cost_merged',
     'v_c_plating_scheme_merged','v_c_raw_bom_merged','v_c_raw_bom_priced',
     'v_c_raw_element_bom_merged','v_c_summary_agg','v_c_tooling_merged','v_c_weight_merged',
     'v_composite_child_elements','v_composite_child_materials',
     'v_composite_child_processes','v_composite_child_weights'
   )
 ORDER BY v.table_name, d.table_name;
