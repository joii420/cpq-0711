SELECT count(*) AS 行数 FROM (
SELECT
  dqm.material_no AS hf_part_no,
  dqm.material_no AS "_物料_销售料号",
  dqm.production_no AS "_物料_生产料号",
  dqcp.customer_product_no AS "_客户料号_客户产品编号",
  dqcp.customer_part_name AS "_客户料号_客户料号名称",
  dqm.category_code AS "_物料_产品分类",
  dqm.material_name AS "_物料_品名",
  dqm.specification AS "_物料_规格",
  dqm.dimension AS "_物料_尺寸",
  dqm.old_material_no AS "_物料_旧料号",
  dqm.material_type AS "_物料_类型"
FROM ds_quote_material dqm
  LEFT JOIN ds_quote_customer_part dqcp ON dqcp.material_no = dqm.material_no AND dqcp.customer_no = 'CUST-0004'
WHERE dqm.material_no = ANY(ARRAY['S0001','S0004','S0008','S0012'])


) x;
