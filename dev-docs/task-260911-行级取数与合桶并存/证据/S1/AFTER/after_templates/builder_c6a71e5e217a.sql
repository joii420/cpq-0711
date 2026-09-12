SELECT
  dqm.material_no AS hf_part_no,
  dqm.material_no AS "_物料_销售料号",
  dqm.material_name AS "_物料_品名",
  dqm.specification AS "_物料_规格",
  dqm.dimension AS "_物料_尺寸",
  dqm.old_material_no AS "_物料_旧料号",
  dqm.unit_weight AS "_物料_单重",
  dqm.production_no AS "_物料_生产料号",
  dqm.material_type AS "_物料_类型",
  dqm.category_code AS "_物料_产品分类"
FROM ds_quote_material dqm
WHERE dqm.material_no = ANY(:total_material_no) AND dqm.customer_no = :customerCode
ORDER BY dqm.material_no
