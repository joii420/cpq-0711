-- C-1 还原脚本（把 v_compat_material_master 退回 V435 之前的定义）
-- 来源：pg_get_viewdef('v_compat_material_master') @ cpq_db_0724，2026-09-09 落 V435 之前
-- 🚨 执行属 DDL，须走 CLAUDE.md §3.2 三步前置 + 用户明确批准本次。
-- ⚠️ 同目录的 v_compat_material_master-改动前定义.sql 只有【视图体】、没有 CREATE 头，不能直接跑。
CREATE OR REPLACE VIEW v_compat_material_master AS
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
   FROM ds_quote_material m
  WHERE NOT (EXISTS ( SELECT 1
           FROM material_master x
          WHERE x.material_no::text = m.material_no::text));
