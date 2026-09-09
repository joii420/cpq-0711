SELECT
  vdcbiofa.production_no AS hf_part_no,
  vdcbiofa.production_no AS "production_no",
  vdcbiofa.item_seq AS "item_seq",
  vdcbiofa.incoming_material_no AS "incoming_material_no",
  vdcbiofa.element_item_seq AS "element_item_seq",
  vdcbiofa.element_name AS "element_name",
  vdcbiofa.ratio_pct AS "ratio_pct",
  vdcbiofa.fee AS "fee",
  vdcbiofa.version_no::text AS view_version
FROM v_ds_cost_basic_incoming_other_fee_all vdcbiofa
WHERE vdcbiofa.production_no IN (SELECT dqm.production_no FROM ds_quote_material dqm WHERE dqm.material_no = ANY(:total_material_no) AND dqm.customer_no = :customerCode) AND :versionFilter(vdcbiofa.is_current, vdcbiofa.version_no::text, vdcbiofa.production_no)
ORDER BY vdcbiofa.production_no, vdcbiofa.incoming_material_no, vdcbiofa.item_seq