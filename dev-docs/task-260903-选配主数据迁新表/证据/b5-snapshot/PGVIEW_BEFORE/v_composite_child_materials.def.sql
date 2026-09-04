 SELECT asy.material_no AS hf_part_no,
    asy.component_no AS child_hf_part_no,
    COALESCE(mm.material_name, mr.name, asy.component_no) AS child_part_name,
    asy.seq_no AS child_seq,
    mr.id AS recipe_id,
    asy.component_no AS material_code,
    mr.symbol AS chemical_symbol,
    COALESCE(asy.component_usage_type, mm.material_type, mr.name, mm.material_name) AS material_name,
    COALESCE(mm.specification, mr.spec_label, asy.component_usage_type) AS spec_label,
    COALESCE(asy.component_usage_type, mr.recipe_type) AS recipe_type,
    c.id AS customer_id,
    NULL::uuid AS quotation_line_item_id
   FROM material_bom_item asy
     LEFT JOIN material_master mm ON mm.material_no::text = asy.component_no::text
     LEFT JOIN material_recipe mr ON mr.code::text = asy.component_no::text
     LEFT JOIN customer c ON c.code::text = asy.customer_no::text
  WHERE asy.system_type::text = 'QUOTE'::text AND asy.characteristic::text IS DISTINCT FROM 'ASSEMBLY'::text AND asy.characteristic::text IS DISTINCT FROM 'OUTSOURCED'::text AND asy.is_current = true
UNION ALL
 SELECT mm.material_no AS hf_part_no,
    mm.material_no AS child_hf_part_no,
    COALESCE(mm.material_name, mm.material_no) AS child_part_name,
    0 AS child_seq,
    NULL::uuid AS recipe_id,
    NULL::character varying AS material_code,
    NULL::character varying AS chemical_symbol,
    COALESCE(mm.material_type, mm.material_name) AS material_name,
    mm.specification AS spec_label,
    mm.material_type AS recipe_type,
    NULL::uuid AS customer_id,
    NULL::uuid AS quotation_line_item_id
   FROM material_master mm
  WHERE NOT (EXISTS ( SELECT 1
           FROM material_bom_item asy2
          WHERE asy2.system_type::text = 'QUOTE'::text AND asy2.characteristic::text IS DISTINCT FROM 'ASSEMBLY'::text AND asy2.is_current = true AND asy2.material_no::text = mm.material_no::text));
