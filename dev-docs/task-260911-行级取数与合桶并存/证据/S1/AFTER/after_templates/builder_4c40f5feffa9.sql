SELECT
  vdcbpafa.production_no AS hf_part_no,
  vdcbpafa.production_no AS "production_no",
  dcbm.material_name AS "material_name",
  vdcbpafa.operation_no AS "operation_no",
  vdcbpafa.process_fee AS "process_fee",
  vdcbpafa.currency AS "currency",
  vdcbpafa.unit AS "unit",
  vdcbpafa.defect_rate AS "defect_rate",
  vdcbpafa.version_no::text AS view_version
FROM v_ds_cost_basic_process_assembly_fee_all vdcbpafa
  LEFT JOIN ds_cost_basic_material dcbm ON dcbm.production_no = vdcbpafa.production_no
WHERE :versionFilter(vdcbpafa.is_current, vdcbpafa.version_no::text, vdcbpafa.production_no) AND vdcbpafa.production_no = ANY(:total_material_no)
ORDER BY vdcbpafa.production_no, vdcbpafa.operation_no
