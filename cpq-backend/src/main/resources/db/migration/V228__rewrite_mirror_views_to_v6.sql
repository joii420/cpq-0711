-- ============================================================
-- V228: 重写 4 个 component_sql_view mirror 视图 SQL → 查 V6 新表
--
-- 触发: AP-53 老表禁用规则 (docs/方案制定前必读.md §V6 基础资料表使用规则)
-- 原 SQL 模板查 V44 mat_part / mat_bom / mat_process，与 V6 导入数据脱节
-- 改写后 FROM V6 表（material_master / material_bom_item / element_bom_item / unit_price / process_master）
-- 注意: V6 表无 quotation_line_item_id 维度，customer × material 共享语义
--       customer_id 列保留 (从 customer 表 JOIN 取 UUID) — 老 declared_columns 含此列
-- ============================================================

-- ============== 1. composite_child_processes_mirror（工序列表）==============
UPDATE component_sql_view SET
    sql_template = $V6$
SELECT
    up.finished_material_no AS hf_part_no,
    up.finished_material_no AS child_hf_part_no,
    COALESCE(mm.material_name, up.finished_material_no) AS child_part_name,
    0 AS child_seq,
    ROW_NUMBER() OVER (PARTITION BY up.finished_material_no, c.id ORDER BY up.operation_no) AS seq_no,
    up.operation_no AS process_code,
    COALESCE(pm.process_name, up.operation_no) AS assembly_process,
    c.id AS customer_id
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
    c.id AS customer_id
FROM material_bom_item asy
LEFT JOIN material_master mm ON mm.material_no = asy.component_no
LEFT JOIN process_master  pm ON pm.process_no  = asy.operation_no
LEFT JOIN customer         c ON c.code         = asy.customer_no
WHERE asy.system_type   = 'QUOTE'
  AND asy.characteristic = 'ASSEMBLY'
  AND asy.operation_no IS NOT NULL
$V6$,
    declared_columns = '[
        {"name":"hf_part_no","dataType":"varchar","nullable":true},
        {"name":"child_hf_part_no","dataType":"varchar","nullable":true},
        {"name":"child_part_name","dataType":"varchar","nullable":true},
        {"name":"child_seq","dataType":"int4","nullable":true},
        {"name":"seq_no","dataType":"int8","nullable":true},
        {"name":"process_code","dataType":"varchar","nullable":true},
        {"name":"assembly_process","dataType":"varchar","nullable":true},
        {"name":"customer_id","dataType":"uuid","nullable":true}
    ]'::jsonb,
    updated_at = NOW()
WHERE sql_view_name = 'composite_child_processes_mirror';

-- ============== 2. composite_child_materials_mirror（材质）==============
UPDATE component_sql_view SET
    sql_template = $V6$
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
    NULL::varchar    AS recipe_type
FROM material_bom_item asy
LEFT JOIN material_master mm ON mm.material_no = asy.component_no
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
    NULL::varchar    AS recipe_type
FROM material_master mm
WHERE NOT EXISTS (
    SELECT 1 FROM material_bom_item asy2
    WHERE asy2.system_type = 'QUOTE'
      AND asy2.characteristic = 'ASSEMBLY'
      AND asy2.material_no = mm.material_no
)
$V6$,
    declared_columns = '[
        {"name":"hf_part_no","dataType":"varchar","nullable":true},
        {"name":"child_hf_part_no","dataType":"varchar","nullable":true},
        {"name":"child_part_name","dataType":"varchar","nullable":true},
        {"name":"child_seq","dataType":"int4","nullable":true},
        {"name":"recipe_id","dataType":"uuid","nullable":true},
        {"name":"material_code","dataType":"varchar","nullable":true},
        {"name":"chemical_symbol","dataType":"varchar","nullable":true},
        {"name":"material_name","dataType":"varchar","nullable":true},
        {"name":"spec_label","dataType":"varchar","nullable":true},
        {"name":"recipe_type","dataType":"varchar","nullable":true}
    ]'::jsonb,
    updated_at = NOW()
WHERE sql_view_name = 'composite_child_materials_mirror';

-- ============== 3. composite_child_elements_mirror（元素含量）==============
UPDATE component_sql_view SET
    sql_template = $V6$
SELECT
    asy.material_no  AS hf_part_no,
    asy.component_no AS child_hf_part_no,
    COALESCE(mm.material_name, asy.component_no) AS child_part_name,
    asy.seq_no       AS child_seq,
    ebi.seq_no       AS seq_no,
    ebi.component_no AS element_name,
    ebi.content      AS composition_pct
FROM material_bom_item asy
JOIN element_bom_item ebi
  ON ebi.system_type   = 'QUOTE'
 AND ebi.customer_no   = asy.customer_no
 AND ebi.material_no   = asy.component_no
LEFT JOIN material_master mm ON mm.material_no = asy.component_no
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
    ebi.content      AS composition_pct
FROM element_bom_item ebi
LEFT JOIN material_master mm ON mm.material_no = ebi.material_no
WHERE ebi.system_type = 'QUOTE'
$V6$,
    declared_columns = '[
        {"name":"hf_part_no","dataType":"varchar","nullable":true},
        {"name":"child_hf_part_no","dataType":"varchar","nullable":true},
        {"name":"child_part_name","dataType":"varchar","nullable":true},
        {"name":"child_seq","dataType":"int4","nullable":true},
        {"name":"seq_no","dataType":"int4","nullable":true},
        {"name":"element_name","dataType":"varchar","nullable":true},
        {"name":"composition_pct","dataType":"numeric","nullable":true}
    ]'::jsonb,
    updated_at = NOW()
WHERE sql_view_name = 'composite_child_elements_mirror';

-- ============== 4. composite_child_weights_mirror（重量）==============
UPDATE component_sql_view SET
    sql_template = $V6$
SELECT
    asy.material_no  AS hf_part_no,
    asy.component_no AS child_hf_part_no,
    COALESCE(mm.material_name, asy.component_no) AS child_part_name,
    asy.seq_no       AS child_seq,
    mm.unit_weight   AS unit_weight,
    'g'::text        AS unit_label
FROM material_bom_item asy
LEFT JOIN material_master mm ON mm.material_no = asy.component_no
WHERE asy.system_type   = 'QUOTE'
  AND asy.characteristic = 'ASSEMBLY'
UNION ALL
SELECT
    mm.material_no   AS hf_part_no,
    mm.material_no   AS child_hf_part_no,
    COALESCE(mm.material_name, mm.material_no) AS child_part_name,
    0                AS child_seq,
    mm.unit_weight   AS unit_weight,
    'g'::text        AS unit_label
FROM material_master mm
WHERE NOT EXISTS (
    SELECT 1 FROM material_bom_item asy2
    WHERE asy2.system_type = 'QUOTE'
      AND asy2.characteristic = 'ASSEMBLY'
      AND asy2.material_no = mm.material_no
)
$V6$,
    declared_columns = '[
        {"name":"hf_part_no","dataType":"varchar","nullable":true},
        {"name":"child_hf_part_no","dataType":"varchar","nullable":true},
        {"name":"child_part_name","dataType":"varchar","nullable":true},
        {"name":"child_seq","dataType":"int4","nullable":true},
        {"name":"unit_weight","dataType":"numeric","nullable":true},
        {"name":"unit_label","dataType":"text","nullable":true}
    ]'::jsonb,
    updated_at = NOW()
WHERE sql_view_name = 'composite_child_weights_mirror';
