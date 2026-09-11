SELECT
  dqm.material_no AS hf_part_no,
  dqcp.customer_product_no AS "_客户料号_客户产品编号",
  dqcp.customer_part_name AS "_客户料号_客户料号名称",
  dqm.material_no AS "_物料_销售料号",
  dqm.specification AS "_物料_规格",
  dqm.dimension AS "_物料_尺寸",
  dqm.material_name AS "_物料_品名",
  dqm.unit_weight AS "_物料_单重",
  dqm.production_no AS "_物料_生产料号"
FROM ds_quote_material dqm
  LEFT JOIN ds_quote_customer_part dqcp ON dqcp.material_no = dqm.material_no AND dqcp.customer_no = :customerCode AND dqcp.customer_product_no = :customerProductNo
WHERE dqm.material_no = ANY(:total_material_no) AND dqm.customer_no = :customerCode
ORDER BY dqm.material_no
