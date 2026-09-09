SELECT
  vdcdpca.production_no AS hf_part_no,
  vdcdpca.production_no AS "production_no",
  vdcdpca.plating_scheme_no AS "plating_scheme_no",
  vdcdpca.plating_version AS "plating_version",
  vdcdpca.plating_process_fee AS "plating_process_fee",
  vdcdpca.plating_material_fee AS "plating_material_fee",
  vdcdpca.currency AS "currency",
  vdcdpca.pricing_unit AS "pricing_unit",
  vdcdpca.defect_rate AS "defect_rate",
  vdcdpca.version_no::text AS view_version
FROM v_ds_cost_detail_plating_cost_all vdcdpca
WHERE vdcdpca.production_no IN (SELECT dqm.production_no FROM ds_quote_material dqm WHERE dqm.material_no = ANY(:total_material_no)) AND :versionFilter(vdcdpca.is_current, vdcdpca.version_no::text, vdcdpca.production_no)
ORDER BY vdcdpca.production_no, vdcdpca.plating_scheme_no, vdcdpca.plating_version