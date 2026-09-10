 SELECT v.id,
    v.material_no,
    v.material_name,
    v.specification,
    v.dimension,
    v.old_material_no,
    v.material_type,
    v.usage_property,
    v.unit_weight,
    v.standard_unit,
    v.created_at,
    v.updated_at,
    v.created_by,
    v.updated_by,
    v.material_recipe_id,
    v.config_fingerprint,
    v.production_no,
    v.pending_quotation_id
   FROM material_master v
UNION ALL
 SELECT md5('dqm:'::text || m.material_no::text)::uuid AS id,
    m.material_no::character varying(20) AS material_no,
    m.material_name::character varying(100) AS material_name,
    m.specification::character varying(100) AS specification,
    m.dimension::character varying(100) AS dimension,
    m.old_material_no::character varying(50) AS old_material_no,
    m.material_type::character varying(50) AS material_type,
    NULL::character varying(50) AS usage_property,
    m.unit_weight::numeric(24,12) AS unit_weight,
    NULL::character varying(20) AS standard_unit,
    m.created_at::timestamp(6) with time zone AS created_at,
    COALESCE(m.updated_at, m.created_at)::timestamp(6) with time zone AS updated_at,
    NULL::uuid AS created_by,
    NULL::uuid AS updated_by,
    NULL::uuid AS material_recipe_id,
    NULL::character varying(80) AS config_fingerprint,
    m.production_no::character varying(32) AS production_no,
    NULL::uuid AS pending_quotation_id
   FROM ( SELECT DISTINCT ON (ds_quote_material.material_no) ds_quote_material.material_no,
            ds_quote_material.material_name,
            ds_quote_material.specification,
            ds_quote_material.dimension,
            ds_quote_material.old_material_no,
            ds_quote_material.material_type,
            ds_quote_material.unit_weight,
            ds_quote_material.created_at,
            ds_quote_material.updated_at,
            ds_quote_material.production_no,
            ds_quote_material.customer_no
           FROM ds_quote_material
          ORDER BY ds_quote_material.material_no, ds_quote_material.customer_no) m
  WHERE NOT (EXISTS ( SELECT 1
           FROM material_master x
          WHERE x.material_no::text = m.material_no::text));
