-- =====================================================================
-- task-260903 · 阶段 A 前置 —— v_compat_material_master.material_type 改直取
--
-- V410 建视图时 ds_quote_material 还没有 material_type 列（依赖另一条线的 V409），
-- 当时只能投影常量 NULL，并在 V410 第 37~39 行留了「V409 落地后必须补一条迁移」的字条。
-- V409 已于 2026-09-03 应用（flyway success=t，取值分布 零件 2 / 外购件 1 / NULL 42），
-- 本迁移就是那张字条的兑现。
--
-- 不补的后果：A-AC-7「外购件料号在 material_type = 外购件」在渲染侧恒空；
-- v_composite_child_materials 第二分支的 COALESCE(mm.material_type, mm.material_name)
-- 与 recipe_type 列对新表侧料号全部降级到兜底值。
--
-- 🚨 用 CREATE OR REPLACE（不 DROP）：v_compat_material_master 被
--    v_composite_child_materials / _processes / _elements 三个视图依赖，DROP 要 CASCADE，
--    那是 CLAUDE.md §3.2 红线。REPLACE 要求列名/列序/类型完全不变 ——
--    这里只把常量 NULL 换成同类型的列引用，两侧仍是 character varying(50)，
--    与 material_master.material_type 逐字一致，故 REPLACE 成立。
--
-- 🚩 类型收窄说明：ds_quote_material.material_type 是 varchar(128)，V6 侧是 varchar(50)。
--    这里 CAST 到 (50) 是为了保住 UNION 的 typmod（PG 只在全分支 typmod 一致时保留，
--    退化成无长度 varchar 会让上述三个依赖视图的 CREATE OR REPLACE 失败）。
--    实际值域由 QuoteRegistry 的 .strictOptions(零件/外购件) 把住，最长 3 个汉字，
--    截断在本项目取值域内不可能发生。
-- =====================================================================

CREATE OR REPLACE VIEW v_compat_material_master AS
SELECT
    v.id,
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
SELECT
    (md5('dqm:' || m.material_no))::uuid AS id,
    (m.material_no)::character varying(20) AS material_no,
    (m.material_name)::character varying(100) AS material_name,
    (m.specification)::character varying(100) AS specification,
    (m.dimension)::character varying(100) AS dimension,
    (m.old_material_no)::character varying(50) AS old_material_no,
    (m.material_type)::character varying(50) AS material_type,
    NULL::character varying(50)        AS usage_property,
    (m.unit_weight)::numeric(24,12) AS unit_weight,
    NULL::character varying(20)        AS standard_unit,
    (m.created_at)::timestamp(6) with time zone AS created_at,
    (COALESCE(m.updated_at, m.created_at))::timestamp(6) with time zone AS updated_at,
    NULL::uuid                         AS created_by,
    NULL::uuid                         AS updated_by,
    NULL::uuid                         AS material_recipe_id,
    NULL::character varying(80)        AS config_fingerprint,
    (m.production_no)::character varying(32) AS production_no,
    NULL::uuid                         AS pending_quotation_id
FROM ds_quote_material m
WHERE NOT EXISTS (
    SELECT 1 FROM material_master x WHERE x.material_no = m.material_no
);
