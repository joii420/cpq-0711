-- ============================================================
-- V239: element_bom_item 加 hf_part_no 独立列 + 重写 elements_mirror
--
-- 背景：Excel Q04 "物料与元素BOM" Sheet 第 1 列是"宏丰料号"(=主件)，
-- 第 2 列才是"投入料号"(=配方料号)。原 2026-05-26 方案文档 §4 决定
-- "宏丰料号不导入"，但 mirror SQL 按 lineItem.partNo (=宏丰料号) 查
-- element_bom_item.material_no (=投入料号) → 完全维度不通 → 选配-元素含量
-- Tab 始终 0 行 (rowCount=0)。
--
-- 决策（用户选择「element_bom_item 加 hf_part_no 独立列(最正规)」方案）：
--   - 加独立 hf_part_no 列保留双维度：material_no=投入料号、hf_part_no=宏丰料号
--   - Q05 元素回收折扣按 material_no+component_no+seq_no 匹配，不受影响
--   - mirror SQL 改用 hf_part_no 作主键
--   - 既有 32 行 element_bom_item 暂保留 hf_part_no=NULL，mirror SQL 跳过
--     (用户重导 Excel 后会按新 Handler 写入 hf_part_no)
-- ============================================================

ALTER TABLE element_bom_item ADD COLUMN IF NOT EXISTS hf_part_no VARCHAR(20);
CREATE INDEX IF NOT EXISTS idx_element_bom_item_hf_part_no
    ON element_bom_item(hf_part_no);

COMMENT ON COLUMN element_bom_item.hf_part_no IS
    'V239: 宏丰成品料号 (Q04 Excel 第 1 列)。与 material_no(投入料号) 并存以支持按主件查询元素 BOM。';

-- ============== 重写 composite_child_elements_mirror SQL ==============
UPDATE component_sql_view SET
    sql_template = $V6$
SELECT
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

-- ============== 同步重建 v_composite_child_elements PG view ==============
DROP VIEW IF EXISTS v_composite_child_elements CASCADE;

CREATE VIEW v_composite_child_elements AS
SELECT
    ebi.hf_part_no   AS hf_part_no,
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
WHERE ebi.system_type = 'QUOTE'
  AND ebi.hf_part_no IS NOT NULL;

COMMENT ON VIEW v_composite_child_elements IS
    'V239 重写：按 element_bom_item.hf_part_no 查主件元素 BOM，与 mirror SQL 同口径。';

-- 自检日志
DO $body$
DECLARE
    cnt_rows  INT;
    cnt_hf    INT;
BEGIN
    SELECT COUNT(*) INTO cnt_rows FROM element_bom_item WHERE system_type='QUOTE';
    SELECT COUNT(*) INTO cnt_hf   FROM element_bom_item WHERE system_type='QUOTE' AND hf_part_no IS NOT NULL;
    RAISE NOTICE 'V239 done: element_bom_item 总 %行, hf_part_no 已填 %行 (其余需要重导 Excel 补全)', cnt_rows, cnt_hf;
END $body$;
