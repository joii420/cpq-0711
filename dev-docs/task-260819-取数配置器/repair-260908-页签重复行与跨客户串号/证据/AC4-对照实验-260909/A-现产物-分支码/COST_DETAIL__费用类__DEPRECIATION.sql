SELECT
  vdcdda.production_no AS hf_part_no,
  vdcdda.production_no AS "production_no",
  vdcdda.operation_no AS "operation_no",
  vdcdda.depreciation_price AS "depreciation_price",
  vdcdda.currency AS "currency",
  vdcdda.unit AS "unit",
  vdcdda.version_no::text AS view_version
FROM v_ds_cost_detail_depreciation_all vdcdda
WHERE vdcdda.production_no IN (SELECT dqm.production_no FROM ds_quote_material dqm WHERE dqm.material_no = ANY(:total_material_no) AND dqm.customer_no = :customerCode) AND :versionFilter(vdcdda.is_current, vdcdda.version_no::text, vdcdda.production_no)
ORDER BY vdcdda.production_no, vdcdda.operation_no