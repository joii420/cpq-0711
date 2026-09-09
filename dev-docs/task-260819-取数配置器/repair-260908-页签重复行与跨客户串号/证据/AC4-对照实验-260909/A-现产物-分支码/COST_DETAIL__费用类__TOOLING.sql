SELECT
  vdcdta.production_no AS hf_part_no,
  vdcdta.production_no AS "production_no",
  vdcdta.operation_no AS "operation_no",
  vdcdta.item_seq AS "item_seq",
  vdcdta.tooling_no AS "tooling_no",
  vdcdta.tooling_cost AS "tooling_cost",
  vdcdta.tooling_life AS "tooling_life",
  vdcdta.cycle_output AS "cycle_output",
  vdcdta.tooling_unit_price AS "tooling_unit_price",
  vdcdta.currency AS "currency",
  vdcdta.unit AS "unit",
  vdcdta.version_no::text AS view_version
FROM v_ds_cost_detail_tooling_all vdcdta
WHERE vdcdta.production_no IN (SELECT dqm.production_no FROM ds_quote_material dqm WHERE dqm.material_no = ANY(:total_material_no) AND dqm.customer_no = :customerCode) AND :versionFilter(vdcdta.is_current, vdcdta.version_no::text, vdcdta.production_no)
ORDER BY vdcdta.production_no, vdcdta.operation_no, vdcdta.tooling_no, vdcdta.item_seq