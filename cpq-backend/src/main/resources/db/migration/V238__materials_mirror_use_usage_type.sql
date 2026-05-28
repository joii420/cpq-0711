-- ============================================================
-- V238: composite_child_materials_mirror + v_composite_child_materials
-- material_name 取 material_bom_item.component_usage_type (V6 Excel "产出料号类型" 列)
-- 之前 V228/V233 取 material_master.material_type, 但子件料号在 material_master 表里通常没有行
-- (V6 Q03 只 upsert 主件 material_master), 导致 material_name = NULL。
-- 改用 material_bom_item.component_usage_type — 该列是 Q03 Sheet "产出料号类型" 列的直接落库,
-- 对每个子件行天然有值（1.银点类 / 2.非银点类 / 组成件 / 边角料）, 是真正的材质语义。
-- ============================================================

-- 1. mirror SQL 视图
UPDATE component_sql_view SET
    sql_template = $V6$
SELECT
    asy.material_no  AS hf_part_no,
    asy.component_no AS child_hf_part_no,
    COALESCE(mm.material_name, asy.component_no) AS child_part_name,
    asy.seq_no       AS child_seq,
    NULL::uuid       AS recipe_id,
    asy.component_no AS material_code,
    NULL::varchar    AS chemical_symbol,
    COALESCE(asy.component_usage_type, mm.material_type, mm.material_name) AS material_name,
    COALESCE(mm.specification, asy.component_usage_type) AS spec_label,
    asy.component_usage_type AS recipe_type
FROM material_bom_item asy
LEFT JOIN material_master mm ON mm.material_no = asy.component_no
WHERE asy.system_type   = 'QUOTE'
  AND asy.characteristic IS NULL
UNION ALL
SELECT
    mm.material_no   AS hf_part_no,
    mm.material_no   AS child_hf_part_no,
    COALESCE(mm.material_name, mm.material_no) AS child_part_name,
    0                AS child_seq,
    NULL::uuid       AS recipe_id,
    NULL::varchar    AS material_code,
    NULL::varchar    AS chemical_symbol,
    COALESCE(mm.material_type, mm.material_name) AS material_name,
    mm.specification AS spec_label,
    mm.material_type AS recipe_type
FROM material_master mm
WHERE NOT EXISTS (
    SELECT 1 FROM material_bom_item asy2
    WHERE asy2.system_type = 'QUOTE'
      AND asy2.characteristic IS NULL
      AND asy2.component_no = mm.material_no
)
$V6$,
    updated_at = NOW()
WHERE sql_view_name = 'composite_child_materials_mirror';

-- 2. 同步 PG view (一致口径 + 加 quotation_line_item_id NULL)
DROP VIEW IF EXISTS v_composite_child_materials CASCADE;

CREATE VIEW v_composite_child_materials AS
SELECT
    asy.material_no  AS hf_part_no,
    asy.component_no AS child_hf_part_no,
    COALESCE(mm.material_name, asy.component_no) AS child_part_name,
    asy.seq_no       AS child_seq,
    NULL::uuid       AS recipe_id,
    asy.component_no AS material_code,
    NULL::varchar    AS chemical_symbol,
    COALESCE(asy.component_usage_type, mm.material_type, mm.material_name) AS material_name,
    COALESCE(mm.specification, asy.component_usage_type) AS spec_label,
    asy.component_usage_type AS recipe_type,
    c.id             AS customer_id,
    NULL::uuid       AS quotation_line_item_id
FROM material_bom_item asy
LEFT JOIN material_master mm ON mm.material_no = asy.component_no
LEFT JOIN customer         c ON c.code         = asy.customer_no
WHERE asy.system_type   = 'QUOTE'
  AND asy.characteristic IS NULL
UNION ALL
SELECT
    mm.material_no   AS hf_part_no,
    mm.material_no   AS child_hf_part_no,
    COALESCE(mm.material_name, mm.material_no) AS child_part_name,
    0                AS child_seq,
    NULL::uuid       AS recipe_id,
    NULL::varchar    AS material_code,
    NULL::varchar    AS chemical_symbol,
    COALESCE(mm.material_type, mm.material_name) AS material_name,
    mm.specification AS spec_label,
    mm.material_type AS recipe_type,
    NULL::uuid       AS customer_id,
    NULL::uuid       AS quotation_line_item_id
FROM material_master mm
WHERE NOT EXISTS (
    SELECT 1 FROM material_bom_item asy2
    WHERE asy2.system_type = 'QUOTE'
      AND asy2.characteristic IS NULL
      AND asy2.component_no = mm.material_no
);

COMMENT ON VIEW v_composite_child_materials IS 'V238 重写：material_name 取 component_usage_type（Q03 Sheet "产出料号类型"列）作为真正的材质语义';
