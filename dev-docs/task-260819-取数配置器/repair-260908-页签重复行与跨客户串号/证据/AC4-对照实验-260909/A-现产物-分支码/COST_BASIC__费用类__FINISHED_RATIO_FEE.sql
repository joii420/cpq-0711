SELECT
  vdcbfrfa.production_no AS hf_part_no,
  vdcbfrfa.production_no AS "production_no",
  vdcbfrfa.item_seq AS "item_seq",
  vdcbfrfa.element_name AS "element_name",
  vdcbfrfa.ratio_pct AS "ratio_pct",
  vdcbfrfa.version_no::text AS view_version
FROM v_ds_cost_basic_finished_ratio_fee_all vdcbfrfa
WHERE vdcbfrfa.production_no IN (SELECT dqm.production_no FROM ds_quote_material dqm WHERE dqm.material_no = ANY(:total_material_no) AND dqm.customer_no = :customerCode) AND :versionFilter(vdcbfrfa.is_current, vdcbfrfa.version_no::text, vdcbfrfa.production_no)
ORDER BY vdcbfrfa.production_no, vdcbfrfa.item_seq