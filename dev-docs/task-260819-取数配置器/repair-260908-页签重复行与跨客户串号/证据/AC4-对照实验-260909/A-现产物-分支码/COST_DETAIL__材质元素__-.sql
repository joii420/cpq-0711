SELECT
  vdcdeba.production_no AS hf_part_no,
  vdcdeba.production_no AS "production_no",
  vdcdeba.material_part_no AS "material_part_no",
  vdcdeba.item_seq AS "item_seq",
  vdcdeba.element_code AS "element_code",
  vdcdeba.content_pct AS "content_pct",
  vdcdeba.loss_rate AS "loss_rate",
  vdcdeba.sales_material_no AS "sales_material_no",
  vdcdeba.version_no::text AS view_version
FROM v_ds_cost_detail_element_bom_all vdcdeba
WHERE vdcdeba.production_no IN (SELECT dqm.production_no FROM ds_quote_material dqm WHERE dqm.material_no = ANY(:total_material_no) AND dqm.customer_no = :customerCode) AND :versionFilter(vdcdeba.is_current, vdcdeba.version_no::text, vdcdeba.production_no)
ORDER BY vdcdeba.production_no, vdcdeba.material_part_no, vdcdeba.element_code, vdcdeba.item_seq