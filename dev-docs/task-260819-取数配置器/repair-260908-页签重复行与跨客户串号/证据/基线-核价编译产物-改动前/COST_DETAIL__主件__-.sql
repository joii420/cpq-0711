SELECT
  dcdm.production_no AS hf_part_no,
  dcdm.production_no AS "production_no",
  dcdm.material_name AS "material_name",
  dcdm.specification AS "specification",
  dcdm.dimension AS "dimension",
  dcdm.old_material_no AS "old_material_no",
  dcdm.unit_weight AS "unit_weight",
  dcdm.material_type AS "material_type"
FROM ds_cost_detail_material dcdm
WHERE dcdm.production_no IN (SELECT dqm.production_no FROM ds_quote_material dqm WHERE dqm.material_no = ANY(:total_material_no))
ORDER BY dcdm.production_no