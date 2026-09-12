SELECT
  dqfof.material_no AS hf_part_no,
  dqfof.material_no AS "_成品其他费用_销售料号",
  dqfof.element_name AS "_成品其他费用_要素名称",
  dqm.material_name AS "_物料_材料名",
  dqfof.ratio_pct AS "_成品其他费用_比例（%）",
  dqfof.pricing_unit AS "_成品其他费用_计价单位"
FROM ds_quote_finished_other_fee dqfof
  LEFT JOIN ds_quote_material dqm ON dqm.material_no = dqfof.material_no AND dqm.customer_no = dqfof.customer_no
WHERE dqfof.material_no = ANY(:total_material_no) AND dqfof.customer_no = :customerCode
ORDER BY dqfof.material_no, dqfof.element_name, dqfof.item_seq
