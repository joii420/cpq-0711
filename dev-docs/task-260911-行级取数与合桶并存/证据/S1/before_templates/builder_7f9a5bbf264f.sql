-- 树契约: material_no=子 / parent_no=父 + :total_material_no; 边式全子件 + 根分支
SELECT
  dqmb.input_material_no AS material_no,
  dqmb.material_no AS parent_no,
  dqmb.input_material_no AS hf_part_no,
  dqmb.material_no AS "_物料BOM_销售料号",
  dqmb.item_seq AS "_物料BOM_项次",
  dqmb.input_material_no AS "_物料BOM_投入料号",
  COALESCE(dqm.material_name, mr.symbol) AS "_物料_材料名",
  dqmb.component_qty AS "_物料BOM_组成数量",
  dqmb.gross_weight AS "_物料BOM_材料毛重",
  dqmb.net_weight AS "_物料BOM_材料净重",
  dqmb.loss_rate AS "_物料BOM_损耗率（%）",
  dqmb.weight_unit AS "_物料BOM_重量单位"
FROM ds_quote_material_bom dqmb
  LEFT JOIN ds_quote_material dqm ON dqm.material_no = dqmb.input_material_no AND dqm.customer_no = dqmb.customer_no
  LEFT JOIN material_recipe mr ON mr.code = dqmb.input_material_no
WHERE dqmb.input_material_no = ANY(:total_material_no) AND dqmb.customer_no = :customerCode
UNION ALL
-- 根分支：本单闭包里无父边的成品自身（树根，parent_no 恒 NULL）
SELECT
  dqm2.material_no,
  NULL::text,
  dqm2.material_no,
  NULL,
  NULL,
  NULL,
  NULL,
  NULL,
  NULL,
  NULL,
  NULL,
  NULL
FROM ds_quote_material dqm2
WHERE dqm2.material_no = ANY(:total_material_no) AND dqm2.customer_no = :customerCode
  AND NOT EXISTS (SELECT 1 FROM ds_quote_material_bom dqmb2 WHERE dqmb2.input_material_no = dqm2.material_no AND dqmb2.customer_no = dqm2.customer_no)
ORDER BY parent_no, material_no, "_物料BOM_项次"
