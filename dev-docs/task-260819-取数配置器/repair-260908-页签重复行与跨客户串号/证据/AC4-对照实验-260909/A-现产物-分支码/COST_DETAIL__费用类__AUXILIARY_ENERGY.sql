SELECT
  vdcdaea.production_no AS hf_part_no,
  vdcdaea.production_no AS "production_no",
  vdcdaea.operation_no AS "operation_no",
  vdcdaea.auxiliary_energy_price AS "auxiliary_energy_price",
  vdcdaea.currency AS "currency",
  vdcdaea.unit AS "unit",
  vdcdaea.version_no::text AS view_version
FROM v_ds_cost_detail_auxiliary_energy_all vdcdaea
WHERE vdcdaea.production_no IN (SELECT dqm.production_no FROM ds_quote_material dqm WHERE dqm.material_no = ANY(:total_material_no) AND dqm.customer_no = :customerCode) AND :versionFilter(vdcdaea.is_current, vdcdaea.version_no::text, vdcdaea.production_no)
ORDER BY vdcdaea.production_no, vdcdaea.operation_no