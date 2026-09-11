INSERT INTO quotation_line_item
  (id, quotation_id, product_id, template_id, product_attribute_values, subtotal, sort_order,
   created_at, customer_part_no, product_name_snapshot, product_part_no_snapshot,
   part_version_locked, composite_type, row_version)
VALUES
 (gen_random_uuid(),'5f70e531-371a-439e-a34c-7304d6ae9981'::uuid,NULL,'839743b3-6876-435d-9aaf-dc088173d104'::uuid,'{}'::jsonb,0,0,now(),'RW-A004','触桥组件A','S0004',0,'SIMPLE',0),
 (gen_random_uuid(),'5f70e531-371a-439e-a34c-7304d6ae9981'::uuid,NULL,'839743b3-6876-435d-9aaf-dc088173d104'::uuid,'{}'::jsonb,0,1,now(),NULL,'触桥组件A','S0004',0,'SIMPLE',0),
 (gen_random_uuid(),'5f70e531-371a-439e-a34c-7304d6ae9981'::uuid,NULL,'839743b3-6876-435d-9aaf-dc088173d104'::uuid,'{}'::jsonb,0,2,now(),'B1SELF-050259','B1SELF-050259','0028-2609000015',0,'SIMPLE',0),
 (gen_random_uuid(),'5f70e531-371a-439e-a34c-7304d6ae9981'::uuid,NULL,'839743b3-6876-435d-9aaf-dc088173d104'::uuid,'{}'::jsonb,0,3,now(),'T0911S3-NOMATCH','触桥组件A','S0004',0,'SIMPLE',0);
