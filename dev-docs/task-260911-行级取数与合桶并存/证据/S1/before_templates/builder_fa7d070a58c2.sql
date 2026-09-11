SELECT
  dqeb.material_no AS hf_part_no,
  dqeb.material_part_no AS "_物料与元素BOM_材质料号"
FROM ds_quote_element_bom dqeb
WHERE dqeb.material_no = ANY(:total_material_no) AND dqeb.customer_no = :customerCode
ORDER BY dqeb.material_no, dqeb.material_part_no, dqeb.element_code, dqeb.item_seq
