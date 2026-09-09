SELECT
  dqpf.material_no AS hf_part_no,
  dqpf.material_no AS "_电镀费用_销售料号",
  dqpf.plating_scheme_no AS "_电镀费用_电镀方案编号",
  dqpf.plating_version AS "_电镀费用_版本编号",
  dqpf.plating_process_fee AS "_电镀费用_电镀加工费",
  dqpf.plating_material_fee AS "_电镀费用_电镀材料费",
  dqpf.currency AS "_电镀费用_货币",
  dqpf.pricing_unit AS "_电镀费用_计价单位",
  dqpf.defect_rate AS "_电镀费用_不良率（%）"
FROM ds_quote_plating_fee dqpf
WHERE dqpf.material_no = ANY(:total_material_no)
ORDER BY dqpf.material_no, dqpf.plating_scheme_no, dqpf.plating_version
