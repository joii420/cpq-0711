-- 树契约: material_no=子 / parent_no=父 + :total_material_no; 边式全子件 + 根分支
SELECT
  dqmb.input_material_no AS material_no,
  dqmb.material_no AS parent_no,
  dqmb.input_material_no AS hf_part_no,
  dqmb.material_no AS "_物料BOM_销售料号",
  dqmb.item_seq AS "_物料BOM_项次",
  dqmb.input_material_no AS "_物料BOM_投入料号",
  dqmb.unit_weight AS "_物料BOM_单重",
  dqmb.output_material_type AS "_物料BOM_产出料号类型",
  dqmb.component_qty AS "_物料BOM_组成数量",
  dqmb.gross_weight AS "_物料BOM_材料毛重",
  dqmb.net_weight AS "_物料BOM_材料净重",
  dqmb.weight_unit AS "_物料BOM_重量单位",
  dqmb.material_ratio AS "_物料BOM_材料占比（%）",
  dqmb.loss_rate AS "_物料BOM_损耗率（%）",
  dqmb.defect_rate AS "_物料BOM_不良率（%）"
FROM ds_quote_material_bom dqmb
WHERE dqmb.input_material_no = ANY(:total_material_no) AND dqmb.customer_no = :customerCode
UNION ALL
-- 根分支：本单闭包里无父边的成品自身（树根，parent_no 恒 NULL）
SELECT
  dqm.material_no,
  NULL::text,
  dqm.material_no,
  NULL,
  NULL,
  NULL,
  NULL,
  NULL,
  NULL,
  NULL,
  NULL,
  NULL,
  NULL,
  NULL,
  NULL
FROM ds_quote_material dqm
WHERE dqm.material_no = ANY(:total_material_no) AND dqm.customer_no = :customerCode
  AND NOT EXISTS (SELECT 1 FROM ds_quote_material_bom dqmb2 WHERE dqmb2.input_material_no = dqm.material_no AND dqmb2.customer_no = dqm.customer_no)
ORDER BY parent_no, material_no, "_物料BOM_项次"
