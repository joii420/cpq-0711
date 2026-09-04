 SELECT ebi.hf_part_no,
    ebi.material_no AS child_hf_part_no,
    COALESCE(mm.material_name, ebi.material_no) AS child_part_name,
    0 AS child_seq,
    ebi.seq_no,
    ebi.component_no AS element_name,
    ebi.content AS composition_pct,
    c.id AS customer_id,
    NULL::uuid AS quotation_line_item_id,
    ebi.material_part_no
   FROM v_compat_element_bom_item ebi
     LEFT JOIN v_compat_material_master mm ON mm.material_no::text = ebi.material_no::text
     LEFT JOIN customer c ON c.code::text = ebi.customer_no::text
  WHERE ebi.system_type::text = 'QUOTE'::text AND ebi.hf_part_no IS NOT NULL AND ebi.is_current = true AND ebi.characteristic::text = (( SELECT max(ebi2.characteristic::text) AS max
           FROM v_compat_element_bom_item ebi2
          WHERE ebi2.system_type::text = ebi.system_type::text AND ebi2.customer_no::text = ebi.customer_no::text AND ebi2.material_no::text = ebi.material_no::text AND NOT ebi2.material_part_no::text IS DISTINCT FROM ebi.material_part_no::text));
