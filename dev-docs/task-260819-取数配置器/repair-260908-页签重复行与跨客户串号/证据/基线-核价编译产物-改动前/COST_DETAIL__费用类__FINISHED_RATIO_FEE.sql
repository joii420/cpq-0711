SELECT
  vdcdfrfa.production_no AS hf_part_no,
  vdcdfrfa.production_no AS "production_no",
  vdcdfrfa.item_seq AS "item_seq",
  vdcdfrfa.element_name AS "element_name",
  vdcdfrfa.ratio_pct AS "ratio_pct",
  vdcdfrfa.version_no::text AS view_version
FROM v_ds_cost_detail_finished_ratio_fee_all vdcdfrfa
WHERE vdcdfrfa.production_no IN (SELECT dqm.production_no FROM ds_quote_material dqm WHERE dqm.material_no = ANY(:total_material_no)) AND :versionFilter(vdcdfrfa.is_current, vdcdfrfa.version_no::text, vdcdfrfa.production_no)
ORDER BY vdcdfrfa.production_no, vdcdfrfa.item_seq