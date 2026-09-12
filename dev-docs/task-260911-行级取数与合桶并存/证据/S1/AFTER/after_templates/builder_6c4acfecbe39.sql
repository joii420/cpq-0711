SELECT
  dqaf.material_no AS hf_part_no,
  dqaf.material_no AS "_组装加工费_销售料号",
  dqaf.item_seq AS "_组装加工费_项次",
  dqaf.assembly_operation AS "_组装加工费_组装工序",
  dqaf.assembly_fee AS "_组装加工费_组装加工费",
  dqaf.currency AS "_组装加工费_货币",
  dqaf.pricing_unit AS "_组装加工费_计价单位",
  dqaf.defect_rate AS "_组装加工费_拒收率/不良率（%）"
FROM ds_quote_assembly_fee dqaf
WHERE dqaf.material_no = ANY(:total_material_no) AND dqaf.customer_no = :customerCode
ORDER BY dqaf.material_no, dqaf.assembly_operation, dqaf.item_seq
