SELECT
  vdcbpafa.production_no AS hf_part_no,
  vdcbpafa.production_no AS "production_no",
  vdcbpafa.operation_no AS "operation_no",
  vdcbpafa.process_fee AS "process_fee",
  vdcbpafa.currency AS "currency",
  vdcbpafa.unit AS "unit",
  vdcbpafa.defect_rate AS "defect_rate",
  vdcbpafa.version_no::text AS view_version
FROM v_ds_cost_basic_process_assembly_fee_all vdcbpafa
WHERE vdcbpafa.production_no IN (SELECT dqm.production_no FROM ds_quote_material dqm WHERE dqm.material_no = ANY(:total_material_no)) AND :versionFilter(vdcbpafa.is_current, vdcbpafa.version_no::text, vdcbpafa.production_no)
ORDER BY vdcbpafa.production_no, vdcbpafa.operation_no