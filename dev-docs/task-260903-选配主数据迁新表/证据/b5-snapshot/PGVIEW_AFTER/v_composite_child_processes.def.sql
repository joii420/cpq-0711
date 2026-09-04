 SELECT up.finished_material_no AS hf_part_no,
    up.finished_material_no AS child_hf_part_no,
    COALESCE(mm.material_name, up.finished_material_no) AS child_part_name,
    0 AS child_seq,
    row_number() OVER (PARTITION BY up.finished_material_no, c.id ORDER BY up.operation_no) AS seq_no,
    up.operation_no AS process_code,
    COALESCE(pm.process_name, up.operation_no) AS assembly_process,
    c.id AS customer_id,
    NULL::uuid AS quotation_line_item_id
   FROM ( SELECT DISTINCT unit_price.customer_no,
            unit_price.finished_material_no,
            unit_price.operation_no
           FROM unit_price
          WHERE unit_price.system_type::text = 'QUOTE'::text AND unit_price.is_current = true AND (unit_price.cost_type::text = ANY (ARRAY['自制加工费'::character varying::text, '组装加工费'::character varying::text, '来料加工费'::character varying::text])) AND unit_price.operation_no IS NOT NULL AND unit_price.finished_material_no IS NOT NULL) up
     LEFT JOIN v_compat_material_master mm ON mm.material_no::text = up.finished_material_no::text
     LEFT JOIN process_master pm ON pm.process_no::text = up.operation_no::text
     LEFT JOIN customer c ON c.code::text = up.customer_no::text
UNION ALL
 SELECT asy.material_no AS hf_part_no,
    asy.component_no AS child_hf_part_no,
    COALESCE(mm.material_name, asy.component_no) AS child_part_name,
    asy.seq_no AS child_seq,
    row_number() OVER (PARTITION BY asy.material_no, c.id, asy.component_no ORDER BY asy.seq_no, asy.operation_no) AS seq_no,
    asy.operation_no AS process_code,
    COALESCE(pm.process_name, asy.operation_no) AS assembly_process,
    c.id AS customer_id,
    NULL::uuid AS quotation_line_item_id
   FROM v_compat_material_bom_item asy
     LEFT JOIN v_compat_material_master mm ON mm.material_no::text = asy.component_no::text
     LEFT JOIN process_master pm ON pm.process_no::text = asy.operation_no::text
     LEFT JOIN customer c ON c.code::text = asy.customer_no::text
  WHERE asy.system_type::text = 'QUOTE'::text AND asy.characteristic::text = 'ASSEMBLY'::text AND asy.is_current = true AND asy.operation_no IS NOT NULL;
