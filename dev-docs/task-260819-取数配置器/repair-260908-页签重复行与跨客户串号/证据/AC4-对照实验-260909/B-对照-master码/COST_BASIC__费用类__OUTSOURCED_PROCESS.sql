SELECT
  vdcbopa.production_no AS hf_part_no,
  vdcbopa.production_no AS "production_no",
  vdcbopa.operation_no AS "operation_no",
  vdcbopa.outsourced_fee AS "outsourced_fee",
  vdcbopa.currency AS "currency",
  vdcbopa.unit AS "unit",
  vdcbopa.version_no::text AS view_version
FROM v_ds_cost_basic_outsourced_process_all vdcbopa
WHERE vdcbopa.production_no IN (SELECT dqm.production_no FROM ds_quote_material dqm WHERE dqm.material_no = ANY(:total_material_no)) AND :versionFilter(vdcbopa.is_current, vdcbopa.version_no::text, vdcbopa.production_no)
ORDER BY vdcbopa.production_no, vdcbopa.operation_no