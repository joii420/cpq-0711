SELECT
  '-- ------------------------------------------------------------' || chr(10) ||
  '-- View: ' || v.table_name || chr(10) ||
  '-- ------------------------------------------------------------' || chr(10) ||
  'CREATE OR REPLACE VIEW public.' || v.table_name || ' AS' || chr(10) ||
  pg_get_viewdef(('public.' || v.table_name)::regclass, true) || chr(10)
  FROM information_schema.views v
 WHERE v.table_schema='public'
   AND v.table_name IN (
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
 ORDER BY
   CASE WHEN v.table_name = 'v_c_raw_bom_priced' THEN 999 ELSE 1 END,
   v.table_name;
