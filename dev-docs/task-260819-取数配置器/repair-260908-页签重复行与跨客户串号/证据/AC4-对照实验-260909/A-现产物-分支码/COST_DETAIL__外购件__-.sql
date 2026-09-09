SELECT
  vdcdmba.production_no AS hf_part_no,
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
WHERE vdcdmba.production_no IN (SELECT dqm.production_no FROM ds_quote_material dqm WHERE dqm.material_no = ANY(:total_material_no) AND dqm.customer_no = :customerCode) AND :versionFilter(vdcdmba.is_current, vdcdmba.version_no::text, vdcdmba.production_no)
ORDER BY vdcdmba.production_no, vdcdmba.component_no, vdcdmba.operation_no, vdcdmba.usage_characteristic, vdcdmba.item_seq