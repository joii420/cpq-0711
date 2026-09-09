-- 树契约: material_no=子 / parent_no=父 + :total_material_no; 边式全子件 + 根分支
SELECT
  vdcdmba.component_no AS material_no,
  vdcdmba.production_no AS parent_no,
  vdcdmba.component_no AS hf_part_no,
  vdcdmba.production_no AS "production_no",
  vdcdmba.item_seq AS "item_seq",
  vdcdmba.component_no AS "component_no",
  vdcdmba.operation_no AS "operation_no",
  vdcdmba.usage_characteristic AS "usage_characteristic",
  vdcdmba.component_qty AS "component_qty",
  vdcdmba.component_qty_unit AS "component_qty_unit",
  vdcdmba.base_qty AS "base_qty",
  vdcdmba.base_qty_unit AS "base_qty_unit",
  vdcdmba.material_loss_rate AS "material_loss_rate",
  vdcdmba.material_fixed_loss AS "material_fixed_loss",
  vdcdmba.defect_rate AS "defect_rate",
  vdcdmba.version_no::text AS view_version
FROM v_ds_cost_detail_material_bom_all vdcdmba
WHERE vdcdmba.component_no IN (SELECT dqm.production_no FROM ds_quote_material dqm WHERE dqm.material_no = ANY(:total_material_no)) AND :versionFilter(vdcdmba.is_current, vdcdmba.version_no::text, vdcdmba.production_no)
UNION ALL
-- 根分支：本单闭包里无父边的成品自身（树根，parent_no 恒 NULL）
SELECT
  dcdm.production_no,
  NULL::text,
  dcdm.production_no,
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
  NULL,
  NULL::text
FROM ds_cost_detail_material dcdm
WHERE dcdm.production_no IN (SELECT dqm2.production_no FROM ds_quote_material dqm2 WHERE dqm2.material_no = ANY(:total_material_no))
  AND NOT EXISTS (SELECT 1 FROM v_ds_cost_detail_material_bom_all vdcdmba2 WHERE vdcdmba2.component_no = dcdm.production_no)
ORDER BY parent_no, material_no, "item_seq"