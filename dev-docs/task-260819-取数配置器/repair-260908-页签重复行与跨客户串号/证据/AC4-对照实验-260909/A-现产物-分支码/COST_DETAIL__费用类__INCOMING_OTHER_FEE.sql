SELECT
  vdcdiofa.production_no AS hf_part_no,
  vdcdiofa.production_no AS "production_no",
  vdcdiofa.item_seq AS "item_seq",
  vdcdiofa.incoming_material_no AS "incoming_material_no",
  vdcdiofa.element_item_seq AS "element_item_seq",
  vdcdiofa.element_name AS "element_name",
  vdcdiofa.ratio_pct AS "ratio_pct",
  vdcdiofa.fee AS "fee",
  vdcdiofa.version_no::text AS view_version
FROM v_ds_cost_detail_incoming_other_fee_all vdcdiofa
WHERE vdcdiofa.production_no IN (SELECT dqm.production_no FROM ds_quote_material dqm WHERE dqm.material_no = ANY(:total_material_no) AND dqm.customer_no = :customerCode) AND :versionFilter(vdcdiofa.is_current, vdcdiofa.version_no::text, vdcdiofa.production_no)
ORDER BY vdcdiofa.production_no, vdcdiofa.incoming_material_no, vdcdiofa.item_seq