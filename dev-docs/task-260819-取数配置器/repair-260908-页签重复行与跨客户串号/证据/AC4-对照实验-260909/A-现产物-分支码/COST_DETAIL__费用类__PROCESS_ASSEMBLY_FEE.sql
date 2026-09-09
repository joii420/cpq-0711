SELECT
  vdcdpafa.production_no AS hf_part_no,
  vdcdpafa.production_no AS "production_no",
  vdcdpafa.operation_no AS "operation_no",
  vdcdpafa.process_fee AS "process_fee",
  vdcdpafa.currency AS "currency",
  vdcdpafa.unit AS "unit",
  vdcdpafa.defect_rate AS "defect_rate",
  vdcdpafa.version_no::text AS view_version
FROM v_ds_cost_detail_process_assembly_fee_all vdcdpafa
WHERE vdcdpafa.production_no IN (SELECT dqm.production_no FROM ds_quote_material dqm WHERE dqm.material_no = ANY(:total_material_no) AND dqm.customer_no = :customerCode) AND :versionFilter(vdcdpafa.is_current, vdcdpafa.version_no::text, vdcdpafa.production_no)
ORDER BY vdcdpafa.production_no, vdcdpafa.operation_no