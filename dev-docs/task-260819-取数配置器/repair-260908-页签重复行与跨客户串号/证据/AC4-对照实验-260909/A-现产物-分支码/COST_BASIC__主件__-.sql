SELECT
  dcbm.production_no AS hf_part_no,
  dcbm.production_no AS "production_no",
  dcbm.material_name AS "material_name",
  dcbm.specification AS "specification",
  dcbm.dimension AS "dimension",
  dcbm.old_material_no AS "old_material_no",
  dcbm.unit_weight AS "unit_weight",
  dcbm.material_type AS "material_type"
FROM ds_cost_basic_material dcbm
WHERE dcbm.production_no IN (SELECT dqm.production_no FROM ds_quote_material dqm WHERE dqm.material_no = ANY(:total_material_no) AND dqm.customer_no = :customerCode)
ORDER BY dcbm.production_no