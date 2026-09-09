SELECT
  dqscf.material_no AS hf_part_no,
  dqscf.material_no AS "_组成件其他费用_销售料号",
  dqscf.item_seq AS "_组成件其他费用_项次",
  dqscf.sub_component_no AS "_组成件其他费用_组成件料号",
  dqscf.supplier_no AS "_组成件其他费用_供应商编号",
  dqscf.supplier_name AS "_组成件其他费用_供应商名称",
  dqscf.element_item_seq AS "_组成件其他费用_要素项次",
  dqscf.element_name AS "_组成件其他费用_要素名称",
  dqscf.value AS "_组成件其他费用_值",
  dqscf.currency AS "_组成件其他费用_货币",
  dqscf.pricing_unit AS "_组成件其他费用_计价单位"
FROM ds_quote_sub_component_fee dqscf
WHERE dqscf.material_no = ANY(:total_material_no)
ORDER BY dqscf.material_no, dqscf.sub_component_no, dqscf.supplier_no, dqscf.supplier_name, dqscf.item_seq
