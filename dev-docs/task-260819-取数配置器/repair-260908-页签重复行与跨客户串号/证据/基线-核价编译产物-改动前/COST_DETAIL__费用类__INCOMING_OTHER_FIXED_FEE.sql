SELECT
  vdcdioffa.production_no AS hf_part_no,
  vdcdioffa.production_no AS "production_no",
  vdcdioffa.item_seq AS "item_seq",
  vdcdioffa.incoming_material_no AS "incoming_material_no",
  vdcdioffa.element_item_seq AS "element_item_seq",
  vdcdioffa.element_name AS "element_name",
  vdcdioffa.fee AS "fee",
  vdcdioffa.currency AS "currency",
  vdcdioffa.pricing_unit AS "pricing_unit",
  vdcdioffa.version_no::text AS view_version
FROM v_ds_cost_detail_incoming_other_fixed_fee_all vdcdioffa
WHERE vdcdioffa.production_no IN (SELECT dqm.production_no FROM ds_quote_material dqm WHERE dqm.material_no = ANY(:total_material_no)) AND :versionFilter(vdcdioffa.is_current, vdcdioffa.version_no::text, vdcdioffa.production_no)
ORDER BY vdcdioffa.production_no, vdcdioffa.incoming_material_no, vdcdioffa.item_seq