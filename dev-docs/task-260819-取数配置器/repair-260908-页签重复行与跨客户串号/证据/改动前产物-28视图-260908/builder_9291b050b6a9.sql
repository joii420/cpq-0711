SELECT
  vdcbeba.production_no AS hf_part_no,
  vdcbeba.production_no AS "production_no",
  mr.symbol AS "symbol",
  vdcbeba.material_part_no AS "material_part_no",
  vdcbeba.item_seq AS "item_seq",
  vdcbeba.element_code AS "element_code",
  vdcbeba.content_pct AS "content_pct",
  vdcbeba.loss_rate AS "loss_rate",
  vdcbeba.version_no::text AS view_version
FROM v_ds_cost_basic_element_bom_all vdcbeba
  LEFT JOIN material_recipe mr ON mr.code = vdcbeba.material_part_no
WHERE vdcbeba.production_no IN (SELECT dqm.production_no FROM ds_quote_material dqm WHERE dqm.material_no = ANY(:total_material_no)) AND :versionFilter(vdcbeba.is_current, vdcbeba.version_no::text, vdcbeba.production_no)
ORDER BY vdcbeba.production_no, vdcbeba.material_part_no, vdcbeba.element_code, vdcbeba.item_seq
