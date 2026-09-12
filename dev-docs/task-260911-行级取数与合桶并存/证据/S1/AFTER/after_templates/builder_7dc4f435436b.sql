SELECT
  dcdm.production_no AS hf_part_no,
  dcdm.production_no AS "production_no",
  dcdm.material_name AS "material_name",
  dcdm.unit_weight AS "unit_weight"
FROM ds_cost_detail_material dcdm
WHERE dcdm.production_no = ANY(:total_material_no)
ORDER BY dcdm.production_no
