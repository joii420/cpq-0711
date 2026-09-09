SELECT
  dqir.material_no AS hf_part_no,
  dqir.material_no AS "_来料回收折扣_销售料号",
  dqir.item_seq AS "_来料回收折扣_项次",
  dqir.input_material_no AS "_来料回收折扣_投入料号",
  dqir.recovery_discount AS "_来料回收折扣_回收折扣（%）",
  dqir.recovery_value AS "_来料回收折扣_回收值",
  dqir.recovery_source AS "_来料回收折扣_回收来源"
FROM ds_quote_incoming_recovery dqir
WHERE dqir.material_no = ANY(:total_material_no)
ORDER BY dqir.material_no, dqir.input_material_no, dqir.item_seq
