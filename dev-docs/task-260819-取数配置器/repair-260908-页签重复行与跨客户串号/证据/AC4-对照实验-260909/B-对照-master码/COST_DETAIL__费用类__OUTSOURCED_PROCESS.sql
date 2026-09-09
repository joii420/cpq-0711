SELECT
  vdcdopa.production_no AS hf_part_no,
  vdcdopa.production_no AS "production_no",
  vdcdopa.operation_no AS "operation_no",
  vdcdopa.outsourced_fee AS "outsourced_fee",
  vdcdopa.currency AS "currency",
  vdcdopa.unit AS "unit",
  vdcdopa.version_no::text AS view_version
FROM v_ds_cost_detail_outsourced_process_all vdcdopa
WHERE vdcdopa.production_no IN (SELECT dqm.production_no FROM ds_quote_material dqm WHERE dqm.material_no = ANY(:total_material_no)) AND :versionFilter(vdcdopa.is_current, vdcdopa.version_no::text, vdcdopa.production_no)
ORDER BY vdcdopa.production_no, vdcdopa.operation_no