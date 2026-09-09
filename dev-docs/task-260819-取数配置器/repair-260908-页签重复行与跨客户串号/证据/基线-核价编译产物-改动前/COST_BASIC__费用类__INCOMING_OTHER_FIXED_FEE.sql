SELECT
  vdcbioffa.production_no AS hf_part_no,
  vdcbioffa.production_no AS "production_no",
  vdcbioffa.item_seq AS "item_seq",
  vdcbioffa.incoming_material_no AS "incoming_material_no",
  vdcbioffa.element_item_seq AS "element_item_seq",
  vdcbioffa.element_name AS "element_name",
  vdcbioffa.fee AS "fee",
  vdcbioffa.currency AS "currency",
  vdcbioffa.pricing_unit AS "pricing_unit",
  vdcbioffa.version_no::text AS view_version
FROM v_ds_cost_basic_incoming_other_fixed_fee_all vdcbioffa
WHERE vdcbioffa.production_no IN (SELECT dqm.production_no FROM ds_quote_material dqm WHERE dqm.material_no = ANY(:total_material_no)) AND :versionFilter(vdcbioffa.is_current, vdcbioffa.version_no::text, vdcbioffa.production_no)
ORDER BY vdcbioffa.production_no, vdcbioffa.incoming_material_no, vdcbioffa.item_seq