-- 树契约: material_no=子 / parent_no=父 + :total_material_no; 边式全子件 + 根分支
SELECT
  vdcbmba.component_no AS material_no,
  vdcbmba.production_no AS parent_no,
  vdcbmba.component_no AS hf_part_no,
  vdcbmba.production_no AS "production_no",
  vdcbmba.item_seq AS "item_seq",
  vdcbmba.component_no AS "component_no",
  COALESCE(dcbm.material_name, mr.symbol) AS "material_name",
  vdcbmba.operation_no AS "operation_no",
  vdcbmba.usage_characteristic AS "usage_characteristic",
  vdcbmba.component_qty AS "component_qty",
  vdcbmba.component_qty_unit AS "component_qty_unit",
  vdcbmba.base_qty AS "base_qty",
  vdcbmba.base_qty_unit AS "base_qty_unit",
  vdcbmba.material_loss_rate AS "material_loss_rate",
  vdcbmba.material_fixed_loss AS "material_fixed_loss",
  vdcbmba.version_no::text AS view_version
FROM v_ds_cost_basic_material_bom_all vdcbmba
  LEFT JOIN ds_cost_basic_material dcbm ON dcbm.production_no = vdcbmba.component_no
  LEFT JOIN material_recipe mr ON mr.code = vdcbmba.component_no
WHERE :versionFilter(vdcbmba.is_current, vdcbmba.version_no::text, vdcbmba.production_no) AND vdcbmba.component_no = ANY(:total_material_no)
UNION ALL
-- 根分支：本单闭包里无父边的成品自身（树根，parent_no 恒 NULL）
SELECT
  dcbm2.production_no,
  NULL::text,
  dcbm2.production_no,
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
FROM ds_cost_basic_material dcbm2
WHERE dcbm2.production_no = ANY(:total_material_no)
  AND NOT EXISTS (SELECT 1 FROM v_ds_cost_basic_material_bom_all vdcbmba2 WHERE vdcbmba2.component_no = dcbm2.production_no)
ORDER BY parent_no, material_no, "item_seq"
