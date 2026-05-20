-- V194: 选配组合产品 — 父级 hfPartNo 聚合各子件 (材质/元素/工序/单重) 的 4 个视图
--
-- 业务诉求 (2026-05-18):
--   选配组合产品流程生成 1 父 lineItem + N 子 lineItem. 前端只渲染 1 个父卡片
--   (compositeType='PART' 隐藏), 父卡片内的 Tab 通过下面 4 个视图按父级 hfPartNo
--   隐式 JOIN 一次拉所有子件的相关数据展示.
--
-- 视图设计原则:
--   - 首列 hf_part_no 语义 = 父级 hfPartNo (mat_bom ASSEMBLY 行的 hf_part_no)
--     让 ImplicitJoinRewriter 自动注入 WHERE hf_part_no = <父级>
--   - 加 child_hf_part_no + child_part_name 列识别行属于哪个子件
--   - 不修改任何物理表, 纯 SQL view, dev 重建无副作用

-- ── 1. v_composite_child_materials — 子件材质 (N 行, 1 子件 1 行) ──────────────
CREATE OR REPLACE VIEW v_composite_child_materials AS
SELECT
    asy.hf_part_no                       AS hf_part_no,         -- 父级 hfPartNo
    asy.input_material_no                AS child_hf_part_no,
    COALESCE(asy.input_material_name, mp_child.part_name, asy.input_material_no) AS child_part_name,
    asy.seq_no                           AS child_seq,
    mp_child.material_recipe_id          AS recipe_id,
    mr.code                              AS material_code,
    mr.symbol                            AS chemical_symbol,
    mr.name                              AS material_name,
    mr.spec_label                        AS spec_label,
    mr.recipe_type                       AS recipe_type
FROM mat_bom asy
LEFT JOIN mat_part mp_child ON mp_child.part_no = asy.input_material_no
LEFT JOIN material_recipe mr ON mr.id = mp_child.material_recipe_id
WHERE asy.bom_type = 'ASSEMBLY';
COMMENT ON VIEW v_composite_child_materials IS
    'V194: 选配组合产品 — 按父 hfPartNo 聚合所有子件的材质字典';

-- ── 2. v_composite_child_elements — 子件元素含量 (sum_N×K 行) ─────────────────
CREATE OR REPLACE VIEW v_composite_child_elements AS
SELECT
    asy.hf_part_no                       AS hf_part_no,         -- 父级 hfPartNo
    asy.input_material_no                AS child_hf_part_no,
    COALESCE(asy.input_material_name, mp_child.part_name, asy.input_material_no) AS child_part_name,
    asy.seq_no                           AS child_seq,
    elem.seq_no                          AS seq_no,             -- 元素行序号 (子件内)
    elem.element_name                    AS element_name,
    elem.composition_pct                 AS composition_pct
FROM mat_bom asy
JOIN mat_bom elem ON elem.hf_part_no = asy.input_material_no
                  AND elem.bom_type = 'ELEMENT'
LEFT JOIN mat_part mp_child ON mp_child.part_no = asy.input_material_no
WHERE asy.bom_type = 'ASSEMBLY';
COMMENT ON VIEW v_composite_child_elements IS
    'V194: 选配组合产品 — 按父 hfPartNo 聚合所有子件的 ELEMENT 行';

-- ── 3. v_composite_child_processes — 子件普通工序 (sum_N×P 行) ───────────────
CREATE OR REPLACE VIEW v_composite_child_processes AS
SELECT
    asy.hf_part_no                       AS hf_part_no,         -- 父级 hfPartNo
    asy.input_material_no                AS child_hf_part_no,
    COALESCE(asy.input_material_name, mp_child.part_name, asy.input_material_no) AS child_part_name,
    asy.seq_no                           AS child_seq,
    proc.seq_no                          AS seq_no,             -- 工序行序号 (子件内)
    proc.process_code                    AS process_code,
    proc.assembly_process                AS assembly_process,
    proc.customer_id                     AS customer_id
FROM mat_bom asy
JOIN mat_process proc ON proc.hf_part_no = asy.input_material_no
                      AND proc.is_current = true
LEFT JOIN mat_part mp_child ON mp_child.part_no = asy.input_material_no
WHERE asy.bom_type = 'ASSEMBLY';
COMMENT ON VIEW v_composite_child_processes IS
    'V194: 选配组合产品 — 按父 hfPartNo 聚合所有子件的 mat_process 工序行';

-- ── 4. v_composite_child_weights — 子件单重 (N 行, 1 子件 1 行) ───────────────
CREATE OR REPLACE VIEW v_composite_child_weights AS
SELECT
    asy.hf_part_no                       AS hf_part_no,         -- 父级 hfPartNo
    asy.input_material_no                AS child_hf_part_no,
    COALESCE(asy.input_material_name, mp_child.part_name, asy.input_material_no) AS child_part_name,
    asy.seq_no                           AS child_seq,
    mp_child.unit_weight                 AS unit_weight,
    'g'                                  AS unit_label
FROM mat_bom asy
LEFT JOIN mat_part mp_child ON mp_child.part_no = asy.input_material_no
WHERE asy.bom_type = 'ASSEMBLY';
COMMENT ON VIEW v_composite_child_weights IS
    'V194: 选配组合产品 — 按父 hfPartNo 聚合所有子件的单重';

-- ── 5. 注册到 basic_data_config 让 BNF resolver 识别 ─────────────────────────
INSERT INTO basic_data_config
    (sheet_name, sheet_index, header_row_index, data_start_row_index,
     description, join_columns, sort_order, status, template_kind, target_table)
SELECT v.sheet, 0, 1, 2, v.desc, '[]'::jsonb, 0, 'ACTIVE', 'QUOTATION', v.table_name
FROM (VALUES
    ('选配组合-子件材质',  '选配组合产品父级 hfPartNo 聚合所有子件材质',  'v_composite_child_materials'),
    ('选配组合-子件元素',  '选配组合产品父级 hfPartNo 聚合所有子件元素行', 'v_composite_child_elements'),
    ('选配组合-子件工序',  '选配组合产品父级 hfPartNo 聚合所有子件 mat_process 工序行', 'v_composite_child_processes'),
    ('选配组合-子件单重',  '选配组合产品父级 hfPartNo 聚合所有子件单重',  'v_composite_child_weights')
) AS v(sheet, "desc", table_name)
WHERE NOT EXISTS (
    SELECT 1 FROM basic_data_config bdc WHERE bdc.target_table = v.table_name
);

-- ── 6. 自检 ─────────────────────────────────────────────────────────────────
DO $$
DECLARE
    v_view_count INT;
    v_bdc_count INT;
BEGIN
    SELECT COUNT(*) INTO v_view_count FROM information_schema.views
        WHERE table_schema = 'public'
          AND table_name IN (
            'v_composite_child_materials',
            'v_composite_child_elements',
            'v_composite_child_processes',
            'v_composite_child_weights');
    SELECT COUNT(*) INTO v_bdc_count FROM basic_data_config
        WHERE target_table LIKE 'v_composite_child_%';
    IF v_view_count <> 4 THEN
        RAISE EXCEPTION 'V194 自检失败: 视图创建数 %!=4', v_view_count;
    END IF;
    IF v_bdc_count <> 4 THEN
        RAISE EXCEPTION 'V194 自检失败: basic_data_config 注册数 %!=4', v_bdc_count;
    END IF;
    RAISE NOTICE 'V194 自检通过: 4 个聚合视图 + basic_data_config 已就位';
END $$;
