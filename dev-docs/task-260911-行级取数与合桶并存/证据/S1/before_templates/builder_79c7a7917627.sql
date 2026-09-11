SELECT
  dcbm.production_no AS hf_part_no,
  dcbm.production_no AS "production_no",
  dcbm.material_name AS "material_name",
  dcbm.dimension AS "dimension",
  dcbm.unit_weight AS "unit_weight"
FROM ds_cost_basic_material dcbm
WHERE dcbm.production_no = ANY(:total_material_no)
ORDER BY dcbm.production_no
