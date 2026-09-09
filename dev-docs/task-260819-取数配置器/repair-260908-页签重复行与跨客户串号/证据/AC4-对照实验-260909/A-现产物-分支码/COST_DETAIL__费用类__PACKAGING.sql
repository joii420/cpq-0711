SELECT
  vdcdpa.production_no AS hf_part_no,
  vdcdpa.production_no AS "production_no",
  vdcdpa.operation_no AS "operation_no",
  vdcdpa.packaging_price AS "packaging_price",
  vdcdpa.currency AS "currency",
  vdcdpa.unit AS "unit",
  vdcdpa.version_no::text AS view_version
FROM v_ds_cost_detail_packaging_all vdcdpa
WHERE vdcdpa.production_no IN (SELECT dqm.production_no FROM ds_quote_material dqm WHERE dqm.material_no = ANY(:total_material_no) AND dqm.customer_no = :customerCode) AND :versionFilter(vdcdpa.is_current, vdcdpa.version_no::text, vdcdpa.production_no)
ORDER BY vdcdpa.production_no, vdcdpa.operation_no