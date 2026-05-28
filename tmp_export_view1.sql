\pset format unaligned
\pset tuples_only on
\pset footer off

SELECT
  '-- ------------------------------------------------------------' || chr(10) ||
  '-- View: ' || v.table_name || chr(10) ||
  '-- ------------------------------------------------------------' || chr(10) ||
  'CREATE OR REPLACE VIEW public.' || v.table_name || ' AS' || chr(10) ||
  pg_get_viewdef(('public.' || v.table_name)::regclass, true) || chr(10)
  FROM information_schema.views v
 WHERE v.table_schema='public'
   AND v.table_name IN (
     'v_q_assembly_merged','v_q_component_fee_merged','v_q_component_merged','v_q_element_merged',
     'v_q_finished_merged','v_q_incoming_merged','v_q_part_info_merged','v_q_plating_merged'
   )
 ORDER BY CASE v.table_name
            WHEN 'v_q_part_info_merged' THEN 1
            WHEN 'v_q_incoming_merged'  THEN 2
            WHEN 'v_q_element_merged'   THEN 3
            WHEN 'v_q_finished_merged'  THEN 4
            WHEN 'v_q_component_merged' THEN 5
            WHEN 'v_q_component_fee_merged' THEN 6
            WHEN 'v_q_assembly_merged'  THEN 7
            WHEN 'v_q_plating_merged'   THEN 8
          END;
