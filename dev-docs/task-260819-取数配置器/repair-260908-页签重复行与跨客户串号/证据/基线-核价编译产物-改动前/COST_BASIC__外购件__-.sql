SELECT
  vdcbmba.production_no AS hf_part_no,
  vdcbmba.production_no AS "production_no",
  vdcbmba.item_seq AS "item_seq",
  vdcbmba.component_no AS "component_no",
  vdcbmba.operation_no AS "operation_no",
  vdcbmba.usage_characteristic AS "usage_characteristic",
  vdcbmba.component_qty AS "component_qty",
  vdcbmba.component_qty_unit AS "component_qty_unit",
  vdcbmba.base_qty AS "base_qty",
  vdcbmba.base_qty_unit AS "base_qty_unit",
  vdcbmba.material_loss_rate AS "material_loss_rate",
  vdcbmba.material_fixed_loss AS "material_fixed_loss",
  vdcbmba.defect_rate AS "defect_rate",
  vdcbmba.version_no::text AS view_version
FROM v_ds_cost_basic_material_bom_all vdcbmba
WHERE vdcbmba.production_no IN (SELECT dqm.production_no FROM ds_quote_material dqm WHERE dqm.material_no = ANY(:total_material_no)) AND :versionFilter(vdcbmba.is_current, vdcbmba.version_no::text, vdcbmba.production_no)
ORDER BY vdcbmba.production_no, vdcbmba.component_no, vdcbmba.operation_no, vdcbmba.usage_characteristic, vdcbmba.item_seq