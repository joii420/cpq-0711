-- ============================================================
-- V263: 5 个选配 SQL 视图加 customer_no = :customerCode 过滤
--
-- 现象：报价单页签数据出现两份。
-- 根因：V6 表是 customer × material 共享，同一料号被多个客户导入后各存一份
--   (如 3120012574 被 CUST-1269 + 8000137 两个客户导入)。
--   zcj_bom / 4 个 mirror 视图 WHERE 只过滤 hf_part_no，未过滤 customer_no
--   → 跨客户数据叠加 → 每个子件返 2 份。
-- 修复：视图 SQL 加 customer_no = :customerCode。
--   :customerCode 由 SqlViewExecutor.enrichCustomerCode 从报价单 customerId(UUID)
--   解析 customer.code 自动绑定 (RuntimeContext.toNamedParams 暴露 :customerId + :customerCode)。
--   material_bom_item / element_bom_item.customer_no 列存的是 code (CUST-1269)。
--
-- dry-run 兼容：SqlViewValidator 把 :customerCode 替换为 NULL，customer_no=NULL 语法合法、
--   返 0 行但 declared_columns 提取不受影响 (LIMIT 0 拿列结构)。
-- ============================================================

-- ============== 1. zcj_bom (选配-子配件清单 driver) ==============
UPDATE component_sql_view SET sql_template = $V6$SELECT
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
    asy.composition_qty composition_qty,
    asy.issue_unit issue_unit
FROM material_bom_item asy
LEFT JOIN material_master mm ON mm.material_no = asy.component_no
WHERE asy.system_type   = 'QUOTE'
  AND asy.characteristic = 'ASSEMBLY'
  AND asy.customer_no = :customerCode$V6$, updated_at = NOW()
WHERE sql_view_name = 'zcj_bom';

-- ============== 2. composite_child_materials_mirror (选配-材质 driver) ==============
UPDATE component_sql_view SET sql_template = $V6$SELECT
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
  AND asy.customer_no = :customerCode$V6$, updated_at = NOW()
WHERE sql_view_name = 'composite_child_materials_mirror';

-- ============== 3. composite_child_processes_mirror (选配-工序列表 driver) ==============
UPDATE component_sql_view SET sql_template = $V6$SELECT
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
  AND asy.customer_no = :customerCode$V6$, updated_at = NOW()
WHERE sql_view_name = 'composite_child_processes_mirror';

-- ============== 4. composite_child_elements_mirror (选配-元素含量 driver) ==============
UPDATE component_sql_view SET sql_template = $V6$SELECT
    ebi.hf_part_no   AS hf_part_no,
    ebi.material_no  AS child_hf_part_no,
    COALESCE(mm.material_name, ebi.material_no) AS child_part_name,
    0                AS child_seq,
    ebi.seq_no       AS seq_no,
    ebi.component_no AS element_name,
    ebi.content      AS composition_pct
FROM element_bom_item ebi
LEFT JOIN material_master mm ON mm.material_no = ebi.material_no
WHERE ebi.system_type = 'QUOTE'
  AND ebi.hf_part_no IS NOT NULL
  AND ebi.customer_no = :customerCode
  AND ebi.characteristic = (
      SELECT MAX(ebi2.characteristic) FROM element_bom_item ebi2
      WHERE ebi2.system_type = ebi.system_type
        AND ebi2.customer_no = ebi.customer_no
        AND ebi2.material_no = ebi.material_no
  )$V6$, updated_at = NOW()
WHERE sql_view_name = 'composite_child_elements_mirror';

-- ============== 5. composite_child_weights_mirror (选配-重量 driver, GLOBAL) ==============
UPDATE component_sql_view SET sql_template = $V6$SELECT
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
  AND asy.customer_no = :customerCode
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
      AND asy2.customer_no = :customerCode
)$V6$, updated_at = NOW()
WHERE sql_view_name = 'composite_child_weights_mirror';

-- 自检日志
DO $body$
DECLARE n INT;
BEGIN
    SELECT COUNT(*) INTO n FROM component_sql_view
    WHERE sql_view_name IN ('zcj_bom','composite_child_materials_mirror','composite_child_processes_mirror','composite_child_elements_mirror','composite_child_weights_mirror')
      AND sql_template LIKE '%:customerCode%';
    RAISE NOTICE 'V263 done: % / 5 个选配视图已加 customer_no = :customerCode 过滤', n;
END $body$;
