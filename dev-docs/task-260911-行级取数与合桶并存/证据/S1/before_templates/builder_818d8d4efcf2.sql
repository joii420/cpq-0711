SELECT
  vdcbopa.production_no AS hf_part_no,
  vdcbopa.production_no AS "production_no",
  dcbm.material_name AS "material_name",
  vdcbopa.operation_no AS "operation_no",
  vdcbopa.version_no::text AS view_version
FROM v_ds_cost_basic_outsourced_process_all vdcbopa
  LEFT JOIN ds_cost_basic_material dcbm ON dcbm.production_no = vdcbopa.production_no
WHERE :versionFilter(vdcbopa.is_current, vdcbopa.version_no::text, vdcbopa.production_no) AND vdcbopa.production_no = ANY(:total_material_no)
ORDER BY vdcbopa.production_no, vdcbopa.operation_no
