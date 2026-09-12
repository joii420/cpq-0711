SELECT
  dqspf.material_no AS hf_part_no,
  dqspf.material_no AS "_自制加工费_销售料号",
  dqspf.item_seq AS "_自制加工费_项次",
  dqspf.input_material_no AS "_自制加工费_投入料号",
  dqm.material_name AS "_物料_材料名",
  dqspf.operation_item_seq AS "_自制加工费_工序项次",
  dqspf.operation_no AS "_自制加工费_工序编号",
  dqspf.value AS "_自制加工费_值",
  dqspf.ratio_pct AS "_自制加工费_比例（%）",
  dqspf.pricing_unit AS "_自制加工费_计价单位"
FROM ds_quote_self_process_fee dqspf
  LEFT JOIN ds_quote_material dqm ON dqm.material_no = dqspf.input_material_no AND dqm.customer_no = dqspf.customer_no
WHERE dqspf.material_no = ANY(:total_material_no) AND dqspf.customer_no = :customerCode
ORDER BY dqspf.material_no, dqspf.input_material_no, dqspf.item_seq
