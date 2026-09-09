SELECT
  dqiff.material_no AS hf_part_no,
  dqiff.material_no AS "_来料固定加工费_销售料号",
  dqiff.input_material_no AS "_来料固定加工费_投入料号",
  dqiff.base_value AS "_来料固定加工费_基准值",
  dqiff.ratio_pct AS "_来料固定加工费_比例（%）"
FROM ds_quote_incoming_fixed_fee dqiff
WHERE dqiff.material_no = ANY(:total_material_no)
ORDER BY dqiff.material_no, dqiff.input_material_no, dqiff.item_seq
