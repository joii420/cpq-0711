SELECT
  vdcdfffa.production_no AS hf_part_no,
  vdcdfffa.production_no AS "production_no",
  vdcdfffa.item_seq AS "item_seq",
  vdcdfffa.element_name AS "element_name",
  vdcdfffa.fee AS "fee",
  vdcdfffa.currency AS "currency",
  vdcdfffa.pricing_unit AS "pricing_unit",
  vdcdfffa.version_no::text AS view_version
FROM v_ds_cost_detail_finished_fixed_fee_all vdcdfffa
WHERE vdcdfffa.production_no IN (SELECT dqm.production_no FROM ds_quote_material dqm WHERE dqm.material_no = ANY(:total_material_no) AND dqm.customer_no = :customerCode) AND :versionFilter(vdcdfffa.is_current, vdcdfffa.version_no::text, vdcdfffa.production_no)
ORDER BY vdcdfffa.production_no, vdcdfffa.item_seq