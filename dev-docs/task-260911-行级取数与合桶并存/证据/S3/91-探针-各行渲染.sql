\pset pager off
\set QUOT :qid
SELECT li.sort_order, COALESCE(li.customer_part_no,'(NULL)') AS li_cpn,
       jsonb_array_length(COALESCE(cd.snapshot_rows,'[]'::jsonb)) AS snap_rows,
       (SELECT string_agg(COALESCE(r->'driverRow'->>'_客户料号_客户产品编号','(EMPTY)'), ',') FROM jsonb_array_elements(cd.snapshot_rows) r) AS rendered_cpn,
       (SELECT string_agg(COALESCE(r->'driverRow'->>'_物料_品名','(EMPTY)'), ',') FROM jsonb_array_elements(cd.snapshot_rows) r) AS rendered_name,
       (SELECT string_agg(COALESCE(r->'driverRow'->>'_物料_单重','(EMPTY)'), ',') FROM jsonb_array_elements(cd.snapshot_rows) r) AS rendered_weight
FROM quotation_line_item li
JOIN quotation_line_component_data cd ON cd.line_item_id=li.id AND cd.component_id='221dc766-8ab6-4d95-82c0-08cc03e6267d'
WHERE li.quotation_id = :'qid' ORDER BY li.sort_order;
