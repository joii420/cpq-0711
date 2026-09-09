SELECT
  dqafa.material_no AS hf_part_no,
  dqafa.material_no AS "_组装加工费年降_销售料号",
  dqafa.item_seq AS "_组装加工费年降_项次",
  dqafa.assembly_operation AS "_组装加工费年降_组装工序",
  dqafa.discount_seq AS "_组装加工费年降_年降顺序",
  dqafa.discount_rate AS "_组装加工费年降_年降系数（%）",
  dqafa.fixed_discount_value AS "_组装加工费年降_单次固定年降值",
  dqafa.currency AS "_组装加工费年降_货币",
  dqafa.pricing_unit AS "_组装加工费年降_计价单位",
  dqafa.discount_times AS "_组装加工费年降_降价次数"
FROM ds_quote_assembly_fee_annual dqafa
WHERE dqafa.material_no = ANY(:total_material_no)
ORDER BY dqafa.material_no, dqafa.assembly_operation, dqafa.discount_seq, dqafa.item_seq
