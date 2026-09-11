SELECT
  dqeb.material_no AS hf_part_no,
  dqeb.material_no AS "_物料与元素BOM_销售料号",
  dqeb.material_part_no AS "_物料与元素BOM_材质料号",
  mr.symbol AS "_材质_材料名",
  dqeb.item_seq AS "_物料与元素BOM_项次",
  dqeb.element_code AS "_物料与元素BOM_元素",
  dqeb.content_pct AS "_物料与元素BOM_组成含量（%）",
  dqeb.loss_rate AS "_物料与元素BOM_损耗率%",
  dqeb.gross_usage AS "_物料与元素BOM_毛用量",
  dqeb.gross_usage_unit AS "_物料与元素BOM_毛用量单位",
  dqeb.net_usage AS "_物料与元素BOM_净用量",
  dqeb.net_usage_unit AS "_物料与元素BOM_净用量单位",
  dqeb.recovery_discount AS "_物料与元素BOM_回收折扣(%)",
  cep.unit_price AS "元素单价"
FROM ds_quote_element_bom dqeb
  LEFT JOIN material_recipe mr ON mr.code = dqeb.material_part_no
  LEFT JOIN f_material_element_price(:customerCode, :priceBaseDate) cep ON cep.element_code = dqeb.element_code AND cep.material_no = dqeb.material_no
WHERE dqeb.material_no = ANY(:total_material_no) AND dqeb.customer_no = :customerCode
ORDER BY dqeb.material_no, dqeb.material_part_no, dqeb.element_code, dqeb.item_seq
