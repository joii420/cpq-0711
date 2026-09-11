SELECT
  dqeb.material_no AS hf_part_no,
  dqeb.material_part_no AS "_物料与元素BOM_材质料号",
  dqeb.element_code AS "_物料与元素BOM_元素",
  cep.unit_price AS "元素单价"
FROM ds_quote_element_bom dqeb
  LEFT JOIN f_material_element_price(:customerCode, :priceBaseDate) cep ON cep.element_code = dqeb.element_code AND cep.material_no = dqeb.material_no
WHERE dqeb.material_no = ANY(:total_material_no) AND dqeb.customer_no = :customerCode
ORDER BY dqeb.material_no, dqeb.material_part_no, dqeb.element_code, dqeb.item_seq
