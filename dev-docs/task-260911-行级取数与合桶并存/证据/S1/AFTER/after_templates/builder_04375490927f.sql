SELECT
  dqiof.material_no AS hf_part_no,
  dqiof.material_no AS "_来料其他费用_销售料号",
  dqiof.item_seq AS "_来料其他费用_项次",
  dqiof.input_material_no AS "_来料其他费用_投入料号",
  dqiof.element_item_seq AS "_来料其他费用_要素项次",
  dqiof.element_name AS "_来料其他费用_要素名称",
  dqiof.value AS "_来料其他费用_值",
  dqiof.ratio_pct AS "_来料其他费用_比例（%）",
  dqiof.currency AS "_来料其他费用_货币",
  dqiof.pricing_unit AS "_来料其他费用_计价单位"
FROM ds_quote_incoming_other_fee dqiof
WHERE dqiof.material_no = ANY(:total_material_no) AND dqiof.customer_no = :customerCode
ORDER BY dqiof.material_no, dqiof.input_material_no, dqiof.item_seq
