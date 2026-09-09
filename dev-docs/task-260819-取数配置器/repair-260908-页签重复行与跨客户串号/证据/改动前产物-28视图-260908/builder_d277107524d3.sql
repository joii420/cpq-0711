SELECT
  dqfof.material_no AS hf_part_no,
  dqfof.material_no AS "_成品其他费用_销售料号",
  dqfof.item_seq AS "_成品其他费用_项次",
  dqfof.element_name AS "_成品其他费用_要素名称",
  dqfof.value AS "_成品其他费用_值",
  dqfof.ratio_pct AS "_成品其他费用_比例（%）",
  dqfof.currency AS "_成品其他费用_货币",
  dqfof.pricing_unit AS "_成品其他费用_计价单位"
FROM ds_quote_finished_other_fee dqfof
WHERE dqfof.material_no = ANY(:total_material_no)
ORDER BY dqfof.material_no, dqfof.element_name, dqfof.item_seq
