SELECT
  dqia.material_no AS hf_part_no,
  dqia.material_no AS "_来料年降_销售料号",
  dqia.item_seq AS "_来料年降_项次",
  dqia.input_material_no AS "_来料年降_投入料号",
  dqia.discount_seq AS "_来料年降_年降顺序",
  dqia.discount_rate AS "_来料年降_年降系数（%）",
  dqia.fixed_discount_value AS "_来料年降_单次固定年降值",
  dqia.currency AS "_来料年降_货币",
  dqia.pricing_unit AS "_来料年降_计价单位",
  dqia.discount_times AS "_来料年降_降价次数"
FROM ds_quote_incoming_annual dqia
WHERE dqia.material_no = ANY(:total_material_no) AND dqia.customer_no = :customerCode
ORDER BY dqia.material_no, dqia.input_material_no, dqia.discount_seq, dqia.item_seq
