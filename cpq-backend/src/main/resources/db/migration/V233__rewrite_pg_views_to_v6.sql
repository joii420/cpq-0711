-- ============================================================
-- V233: 重写 4 个老 PG view 查 V6 表
--
-- v_composite_child_processes / materials / elements / weights
-- 列结构与 V228 mirror SQL 视图同口径；额外保留 quotation_line_item_id 列（值=NULL）
-- 让 ImplicitJoinRewriter 在 schema 检查时不抱怨列缺失（V6 设计语义：customer×material 共享，无 lineItem 维度）
--
-- 注意：DROP VIEW CASCADE 后必须 touch java 文件重启 Quarkus 清 tableColumnsCache (见 CLAUDE.md §视图重启)
-- ============================================================

-- ============== 1. v_composite_child_processes ==============
DROP VIEW IF EXISTS v_composite_child_processes CASCADE;

CREATE VIEW v_composite_child_processes AS
SELECT
    up.finished_material_no AS hf_part_no,
    up.finished_material_no AS child_hf_part_no,
    COALESCE(mm.material_name, up.finished_material_no) AS child_part_name,
    0 AS child_seq,
    ROW_NUMBER() OVER (PARTITION BY up.finished_material_no, c.id ORDER BY up.operation_no) AS seq_no,
    up.operation_no AS process_code,
    COALESCE(pm.process_name, up.operation_no) AS assembly_process,
    c.id AS customer_id,
    NULL::uuid AS quotation_line_item_id
FROM (
    SELECT DISTINCT customer_no, finished_material_no, operation_no
    FROM unit_price
    WHERE system_type = 'QUOTE'
      AND cost_type IN ('自制加工费', '组装加工费', '来料加工费')
      AND operation_no IS NOT NULL
      AND finished_material_no IS NOT NULL
) up
LEFT JOIN material_master mm ON mm.material_no = up.finished_material_no
LEFT JOIN process_master  pm ON pm.process_no  = up.operation_no
LEFT JOIN customer         c ON c.code         = up.customer_no
UNION ALL
SELECT
    asy.material_no  AS hf_part_no,
    asy.component_no AS child_hf_part_no,
    COALESCE(mm.material_name, asy.component_no) AS child_part_name,
    asy.seq_no       AS child_seq,
    ROW_NUMBER() OVER (PARTITION BY asy.material_no, c.id, asy.component_no ORDER BY asy.seq_no, asy.operation_no) AS seq_no,
    asy.operation_no AS process_code,
    COALESCE(pm.process_name, asy.operation_no) AS assembly_process,
    c.id AS customer_id,
    NULL::uuid AS quotation_line_item_id
FROM material_bom_item asy
LEFT JOIN material_master mm ON mm.material_no = asy.component_no
LEFT JOIN process_master  pm ON pm.process_no  = asy.operation_no
LEFT JOIN customer         c ON c.code         = asy.customer_no
WHERE asy.system_type   = 'QUOTE'
  AND asy.characteristic = 'ASSEMBLY'
  AND asy.operation_no IS NOT NULL;

COMMENT ON VIEW v_composite_child_processes IS 'V233 重写：查 V6 表 (unit_price + material_bom_item + material_master + process_master + customer)；quotation_line_item_id=NULL（V6 customer×material 共享语义）';

-- ============== 2. v_composite_child_materials ==============
DROP VIEW IF EXISTS v_composite_child_materials CASCADE;

CREATE VIEW v_composite_child_materials AS
SELECT
    asy.material_no  AS hf_part_no,
    asy.component_no AS child_hf_part_no,
    COALESCE(mm.material_name, asy.component_no) AS child_part_name,
    asy.seq_no       AS child_seq,
    NULL::uuid       AS recipe_id,
    NULL::varchar    AS material_code,
    NULL::varchar    AS chemical_symbol,
    COALESCE(mm.material_type, mm.material_name) AS material_name,
    mm.specification AS spec_label,
    NULL::varchar    AS recipe_type,
    c.id             AS customer_id,
    NULL::uuid       AS quotation_line_item_id
FROM material_bom_item asy
LEFT JOIN material_master mm ON mm.material_no = asy.component_no
LEFT JOIN customer         c ON c.code         = asy.customer_no
WHERE asy.system_type   = 'QUOTE'
  AND asy.characteristic = 'ASSEMBLY'
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
    NULL::varchar    AS recipe_type,
    NULL::uuid       AS customer_id,
    NULL::uuid       AS quotation_line_item_id
FROM material_master mm
WHERE NOT EXISTS (
    SELECT 1 FROM material_bom_item asy2
    WHERE asy2.system_type = 'QUOTE'
      AND asy2.characteristic = 'ASSEMBLY'
      AND asy2.material_no = mm.material_no
);

COMMENT ON VIEW v_composite_child_materials IS 'V233 重写：查 V6 material_bom_item + material_master';

-- ============== 3. v_composite_child_elements ==============
DROP VIEW IF EXISTS v_composite_child_elements CASCADE;

CREATE VIEW v_composite_child_elements AS
SELECT
    asy.material_no  AS hf_part_no,
    asy.component_no AS child_hf_part_no,
    COALESCE(mm.material_name, asy.component_no) AS child_part_name,
    asy.seq_no       AS child_seq,
    ebi.seq_no       AS seq_no,
    ebi.component_no AS element_name,
    ebi.content      AS composition_pct,
    c.id             AS customer_id,
    NULL::uuid       AS quotation_line_item_id
FROM material_bom_item asy
JOIN element_bom_item ebi
  ON ebi.system_type = 'QUOTE'
 AND ebi.customer_no = asy.customer_no
 AND ebi.material_no = asy.component_no
LEFT JOIN material_master mm ON mm.material_no = asy.component_no
LEFT JOIN customer         c ON c.code         = asy.customer_no
WHERE asy.system_type   = 'QUOTE'
  AND asy.characteristic = 'ASSEMBLY'
UNION ALL
SELECT
    ebi.material_no  AS hf_part_no,
    ebi.material_no  AS child_hf_part_no,
    COALESCE(mm.material_name, ebi.material_no) AS child_part_name,
    0                AS child_seq,
    ebi.seq_no       AS seq_no,
    ebi.component_no AS element_name,
    ebi.content      AS composition_pct,
    c.id             AS customer_id,
    NULL::uuid       AS quotation_line_item_id
FROM element_bom_item ebi
LEFT JOIN material_master mm ON mm.material_no = ebi.material_no
LEFT JOIN customer         c ON c.code         = ebi.customer_no
WHERE ebi.system_type = 'QUOTE';

COMMENT ON VIEW v_composite_child_elements IS 'V233 重写：查 V6 element_bom_item + material_bom_item + material_master';

-- ============== 4. v_composite_child_weights ==============
DROP VIEW IF EXISTS v_composite_child_weights CASCADE;

CREATE VIEW v_composite_child_weights AS
SELECT
    asy.material_no  AS hf_part_no,
    asy.component_no AS child_hf_part_no,
    COALESCE(mm.material_name, asy.component_no) AS child_part_name,
    asy.seq_no       AS child_seq,
    mm.unit_weight   AS unit_weight,
    'g'::text        AS unit_label,
    c.id             AS customer_id,
    NULL::uuid       AS quotation_line_item_id
FROM material_bom_item asy
LEFT JOIN material_master mm ON mm.material_no = asy.component_no
LEFT JOIN customer         c ON c.code         = asy.customer_no
WHERE asy.system_type   = 'QUOTE'
  AND asy.characteristic = 'ASSEMBLY'
UNION ALL
SELECT
    mm.material_no   AS hf_part_no,
    mm.material_no   AS child_hf_part_no,
    COALESCE(mm.material_name, mm.material_no) AS child_part_name,
    0                AS child_seq,
    mm.unit_weight   AS unit_weight,
    'g'::text        AS unit_label,
    NULL::uuid       AS customer_id,
    NULL::uuid       AS quotation_line_item_id
FROM material_master mm
WHERE NOT EXISTS (
    SELECT 1 FROM material_bom_item asy2
    WHERE asy2.system_type = 'QUOTE'
      AND asy2.characteristic = 'ASSEMBLY'
      AND asy2.material_no = mm.material_no
);

COMMENT ON VIEW v_composite_child_weights IS 'V233 重写：查 V6 material_bom_item + material_master.unit_weight';
