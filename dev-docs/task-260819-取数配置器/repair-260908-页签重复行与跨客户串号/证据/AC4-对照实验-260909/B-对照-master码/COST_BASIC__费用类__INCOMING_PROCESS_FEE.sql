SELECT
  vdcbipfa.production_no AS hf_part_no,
  vdcbipfa.production_no AS "production_no",
  vdcbipfa.item_seq AS "item_seq",
  vdcbipfa.incoming_material_no AS "incoming_material_no",
  vdcbipfa.process_fee AS "process_fee",
  vdcbipfa.currency AS "currency",
  vdcbipfa.unit AS "unit",
  vdcbipfa.loss_rate AS "loss_rate",
  vdcbipfa.version_no::text AS view_version
FROM v_ds_cost_basic_incoming_process_fee_all vdcbipfa
WHERE vdcbipfa.production_no IN (SELECT dqm.production_no FROM ds_quote_material dqm WHERE dqm.material_no = ANY(:total_material_no)) AND :versionFilter(vdcbipfa.is_current, vdcbipfa.version_no::text, vdcbipfa.production_no)
ORDER BY vdcbipfa.production_no, vdcbipfa.incoming_material_no, vdcbipfa.item_seq