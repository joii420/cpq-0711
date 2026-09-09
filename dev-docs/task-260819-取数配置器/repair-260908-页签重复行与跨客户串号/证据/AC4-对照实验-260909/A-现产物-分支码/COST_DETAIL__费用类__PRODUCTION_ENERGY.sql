SELECT
  vdcdpea.production_no AS hf_part_no,
  vdcdpea.production_no AS "production_no",
  vdcdpea.operation_no AS "operation_no",
  vdcdpea.production_energy_price AS "production_energy_price",
  vdcdpea.currency AS "currency",
  vdcdpea.unit AS "unit",
  vdcdpea.version_no::text AS view_version
FROM v_ds_cost_detail_production_energy_all vdcdpea
WHERE vdcdpea.production_no IN (SELECT dqm.production_no FROM ds_quote_material dqm WHERE dqm.material_no = ANY(:total_material_no) AND dqm.customer_no = :customerCode) AND :versionFilter(vdcdpea.is_current, vdcdpea.version_no::text, vdcdpea.production_no)
ORDER BY vdcdpea.production_no, vdcdpea.operation_no