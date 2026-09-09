SELECT
  vdcdca.production_no AS hf_part_no,
  vdcdca.production_no AS "production_no",
  vdcdca.operation_no AS "operation_no",
  vdcdca.labor_std_price AS "labor_std_price",
  vdcdca.currency AS "currency",
  vdcdca.unit AS "unit",
  vdcdca.version_no::text AS view_version
FROM v_ds_cost_detail_capacity_all vdcdca
WHERE vdcdca.production_no IN (SELECT dqm.production_no FROM ds_quote_material dqm WHERE dqm.material_no = ANY(:total_material_no)) AND :versionFilter(vdcdca.is_current, vdcdca.version_no::text, vdcdca.production_no)
ORDER BY vdcdca.production_no, vdcdca.operation_no