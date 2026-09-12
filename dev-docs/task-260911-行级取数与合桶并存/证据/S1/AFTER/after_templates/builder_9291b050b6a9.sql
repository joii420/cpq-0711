SELECT
  vdcbeba.production_no AS hf_part_no,
  vdcbeba.production_no AS "production_no",
  mr.symbol AS "symbol",
  vdcbeba.material_part_no AS "material_part_no",
  vdcbeba.item_seq AS "item_seq",
  vdcbeba.element_code AS "element_code",
  vdcbeba.content_pct AS "content_pct",
  vdcbeba.loss_rate AS "loss_rate",
  cep.unit_price AS "元素单价",
  vdcbeba.version_no::text AS view_version
FROM v_ds_cost_basic_element_bom_all vdcbeba
  LEFT JOIN material_recipe mr ON mr.code = vdcbeba.material_part_no
  LEFT JOIN f_customer_element_price(:customerCode, :priceBaseDate) cep ON cep.element_code = vdcbeba.element_code
WHERE :versionFilter(vdcbeba.is_current, vdcbeba.version_no::text, vdcbeba.production_no) AND vdcbeba.production_no = ANY(:total_material_no)
ORDER BY vdcbeba.production_no, vdcbeba.material_part_no, vdcbeba.element_code, vdcbeba.item_seq
