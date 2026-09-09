SELECT
  vdcbfffa.production_no AS hf_part_no,
  vdcbfffa.production_no AS "production_no",
  vdcbfffa.item_seq AS "item_seq",
  vdcbfffa.element_name AS "element_name",
  vdcbfffa.fee AS "fee",
  vdcbfffa.currency AS "currency",
  vdcbfffa.pricing_unit AS "pricing_unit",
  vdcbfffa.version_no::text AS view_version
FROM v_ds_cost_basic_finished_fixed_fee_all vdcbfffa
WHERE vdcbfffa.production_no IN (SELECT dqm.production_no FROM ds_quote_material dqm WHERE dqm.material_no = ANY(:total_material_no) AND dqm.customer_no = :customerCode) AND :versionFilter(vdcbfffa.is_current, vdcbfffa.version_no::text, vdcbfffa.production_no)
ORDER BY vdcbfffa.production_no, vdcbfffa.element_name, vdcbfffa.item_seq