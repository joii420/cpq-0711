\pset pager off
SELECT li.sort_order, COALESCE(li.customer_part_no,'(NULL)') AS li_cpn, c.name AS tab,
       jsonb_array_length(COALESCE(cd.snapshot_rows,'[]'::jsonb)) AS rows,
       cd.subtotal
FROM quotation_line_item li
JOIN quotation_line_component_data cd ON cd.line_item_id=li.id
JOIN component c ON c.id=cd.component_id
WHERE li.quotation_id = :'qid' ORDER BY li.sort_order, c.name;
