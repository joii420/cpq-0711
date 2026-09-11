SELECT
  dqiff.material_no AS hf_part_no,
  dqiff.material_no AS "_来料固定加工费_销售料号",
  dqiff.item_seq AS "_来料固定加工费_项次",
  dqm.material_name AS "_物料_材料名",
  dqiff.input_material_no AS "_来料固定加工费_投入料号",
  dqiff.base_value AS "_来料固定加工费_基准值",
  dqiff.ratio_pct AS "_来料固定加工费_比例（%）"
FROM ds_quote_incoming_fixed_fee dqiff
  LEFT JOIN ds_quote_material dqm ON dqm.material_no = dqiff.input_material_no AND dqm.customer_no = dqiff.customer_no
WHERE dqiff.material_no = ANY(:total_material_no) AND dqiff.customer_no = :customerCode
ORDER BY dqiff.material_no, dqiff.input_material_no, dqiff.item_seq
