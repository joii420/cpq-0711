SELECT
  vdcdipfa.production_no AS hf_part_no,
  vdcdipfa.production_no AS "production_no",
  vdcdipfa.item_seq AS "item_seq",
  vdcdipfa.incoming_material_no AS "incoming_material_no",
  vdcdipfa.process_fee AS "process_fee",
  vdcdipfa.currency AS "currency",
  vdcdipfa.unit AS "unit",
  vdcdipfa.loss_rate AS "loss_rate",
  vdcdipfa.version_no::text AS view_version
FROM v_ds_cost_detail_incoming_process_fee_all vdcdipfa
WHERE vdcdipfa.production_no IN (SELECT dqm.production_no FROM ds_quote_material dqm WHERE dqm.material_no = ANY(:total_material_no)) AND :versionFilter(vdcdipfa.is_current, vdcdipfa.version_no::text, vdcdipfa.production_no)
ORDER BY vdcdipfa.production_no, vdcdipfa.incoming_material_no, vdcdipfa.item_seq