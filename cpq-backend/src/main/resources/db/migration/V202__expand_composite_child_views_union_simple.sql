-- V202: 扩展 v_composite_child_* 视图加 UNION SIMPLE 分支 — 让组合产品模板的引用路径
--       自适配 SIMPLE / COMPOSITE 两种产品
--
-- 背景 (2026-05-19 QT-20260519-1413 排查):
--   V194/V196 创建的 4 个视图只有 COMPOSITE 分支 (WHERE asy.bom_type='ASSEMBLY'),
--   用户在同 quote 里混选 SIMPLE 产品时 → mat_bom 无 ASSEMBLY 行 → 视图返 0 行
--   → auto-hide 隐藏所有 Tab → "请通过添加产品选择模板"占位.
--
-- 设计原则 (与配置中心 3 层架构一致):
--   - 不动模板 / component / template_component override / BNF resolver / 前端
--   - 只动视图层 (CREATE OR REPLACE) — 视图的"语义"从 "COMPOSITE 父级聚合" 扩到
--     "按产品类型自适应展示子件/自身明细"
--   - 两分支互斥: 分支 A WHERE bom_type='ASSEMBLY'; 分支 B WHERE NOT EXISTS ASSEMBLY
--   - 保留旧视图名 (basic_data_config / V201 override / snapshot 全不动)
--
-- 分支语义:
--   A. COMPOSITE 父级 (有 ASSEMBLY 行): 聚合所有子件的材质/元素/工序/单重 — V196 原逻辑
--   B. SIMPLE 自身 (无 ASSEMBLY 行): 直接拿 mat_part / mat_bom ELEMENT / mat_process /
--      mat_part.unit_weight, child_part_name 字段塞料号自身 (UI 不显"子件"维度)
--
-- 注意: V202 用 CREATE OR REPLACE 不 DROP, 不破坏依赖. 但视图列结构改了
--       (没改, 只是 UNION 加分支, 列数+类型完全一致), CACHE 仍可能残留 — Quarkus
--       需重启 (CLAUDE.md 自检规约: "视图 DROP CASCADE / 重建后必须重启").

-- ── 1. v_composite_child_materials ─────────────────────────────────────────
CREATE OR REPLACE VIEW v_composite_child_materials AS
-- 分支 A: COMPOSITE 父级 → 子件材质聚合
SELECT
    asy.hf_part_no                          AS hf_part_no,
    COALESCE(asy.child_part_no, asy.input_material_no)  AS child_hf_part_no,
    COALESCE(mp_child.part_name, asy.input_material_name,
             asy.child_part_no, asy.input_material_no)  AS child_part_name,
    asy.seq_no                              AS child_seq,
    mp_child.material_recipe_id             AS recipe_id,
    mr.code                                 AS material_code,
    mr.symbol                               AS chemical_symbol,
    mr.name                                 AS material_name,
    mr.spec_label                           AS spec_label,
    mr.recipe_type                          AS recipe_type
FROM mat_bom asy
LEFT JOIN mat_part mp_child
       ON mp_child.part_no = COALESCE(asy.child_part_no, asy.input_material_no)
LEFT JOIN material_recipe mr ON mr.id = mp_child.material_recipe_id
WHERE asy.bom_type = 'ASSEMBLY'

UNION ALL

-- 分支 B: SIMPLE 自身 → 直接拿 mat_part 自己的材质
SELECT
    mp.part_no                              AS hf_part_no,
    mp.part_no                              AS child_hf_part_no,
    COALESCE(mp.part_name, mp.part_no)      AS child_part_name,
    0                                       AS child_seq,
    mp.material_recipe_id                   AS recipe_id,
    mr.code                                 AS material_code,
    mr.symbol                               AS chemical_symbol,
    mr.name                                 AS material_name,
    mr.spec_label                           AS spec_label,
    mr.recipe_type                          AS recipe_type
FROM mat_part mp
LEFT JOIN material_recipe mr ON mr.id = mp.material_recipe_id
WHERE NOT EXISTS (
    SELECT 1 FROM mat_bom WHERE hf_part_no = mp.part_no AND bom_type = 'ASSEMBLY'
);

COMMENT ON VIEW v_composite_child_materials IS
    'V194/V196/V202: 产品材质明细 — A) 有 ASSEMBLY 行 → 子件聚合 / B) 无 ASSEMBLY 行 → 自身明细';

-- ── 2. v_composite_child_elements ───────────────────────────────────────────
CREATE OR REPLACE VIEW v_composite_child_elements AS
-- 分支 A: COMPOSITE 父级 → 所有子件 ELEMENT 行
SELECT
    asy.hf_part_no                          AS hf_part_no,
    COALESCE(asy.child_part_no, asy.input_material_no)  AS child_hf_part_no,
    COALESCE(mp_child.part_name, asy.input_material_name,
             asy.child_part_no, asy.input_material_no)  AS child_part_name,
    asy.seq_no                              AS child_seq,
    elem.seq_no                             AS seq_no,
    elem.element_name                       AS element_name,
    elem.composition_pct                    AS composition_pct
FROM mat_bom asy
JOIN mat_bom elem ON elem.hf_part_no = COALESCE(asy.child_part_no, asy.input_material_no)
                  AND elem.bom_type = 'ELEMENT'
LEFT JOIN mat_part mp_child
       ON mp_child.part_no = COALESCE(asy.child_part_no, asy.input_material_no)
WHERE asy.bom_type = 'ASSEMBLY'

UNION ALL

-- 分支 B: SIMPLE 自身 → 直接拿 ELEMENT 行
SELECT
    elem.hf_part_no                         AS hf_part_no,
    elem.hf_part_no                         AS child_hf_part_no,
    COALESCE(mp.part_name, elem.hf_part_no) AS child_part_name,
    0                                       AS child_seq,
    elem.seq_no                             AS seq_no,
    elem.element_name                       AS element_name,
    elem.composition_pct                    AS composition_pct
FROM mat_bom elem
LEFT JOIN mat_part mp ON mp.part_no = elem.hf_part_no
WHERE elem.bom_type = 'ELEMENT'
  AND NOT EXISTS (
      SELECT 1 FROM mat_bom WHERE hf_part_no = elem.hf_part_no AND bom_type = 'ASSEMBLY'
  );

COMMENT ON VIEW v_composite_child_elements IS
    'V194/V196/V202: 产品元素含量明细 — A) 有 ASSEMBLY 行 → 子件 ELEMENT 聚合 / B) 无 ASSEMBLY 行 → 自身 ELEMENT';

-- ── 3. v_composite_child_processes ──────────────────────────────────────────
CREATE OR REPLACE VIEW v_composite_child_processes AS
-- 分支 A: COMPOSITE 父级 → 所有子件 mat_process 行
SELECT
    asy.hf_part_no                          AS hf_part_no,
    COALESCE(asy.child_part_no, asy.input_material_no)  AS child_hf_part_no,
    COALESCE(mp_child.part_name, asy.input_material_name,
             asy.child_part_no, asy.input_material_no)  AS child_part_name,
    asy.seq_no                              AS child_seq,
    proc.seq_no                             AS seq_no,
    proc.process_code                       AS process_code,
    proc.assembly_process                   AS assembly_process,
    proc.customer_id                        AS customer_id
FROM mat_bom asy
JOIN mat_process proc ON proc.hf_part_no = COALESCE(asy.child_part_no, asy.input_material_no)
                      AND proc.is_current = true
LEFT JOIN mat_part mp_child
       ON mp_child.part_no = COALESCE(asy.child_part_no, asy.input_material_no)
WHERE asy.bom_type = 'ASSEMBLY'

UNION ALL

-- 分支 B: SIMPLE 自身 → 直接拿 mat_process 行
SELECT
    proc.hf_part_no                         AS hf_part_no,
    proc.hf_part_no                         AS child_hf_part_no,
    COALESCE(mp.part_name, proc.hf_part_no) AS child_part_name,
    0                                       AS child_seq,
    proc.seq_no                             AS seq_no,
    proc.process_code                       AS process_code,
    proc.assembly_process                   AS assembly_process,
    proc.customer_id                        AS customer_id
FROM mat_process proc
LEFT JOIN mat_part mp ON mp.part_no = proc.hf_part_no
WHERE proc.is_current = true
  AND NOT EXISTS (
      SELECT 1 FROM mat_bom WHERE hf_part_no = proc.hf_part_no AND bom_type = 'ASSEMBLY'
  );

COMMENT ON VIEW v_composite_child_processes IS
    'V194/V196/V202: 产品工序明细 — A) 有 ASSEMBLY 行 → 子件 mat_process 聚合 / B) 无 ASSEMBLY 行 → 自身 mat_process';

-- ── 4. v_composite_child_weights ────────────────────────────────────────────
CREATE OR REPLACE VIEW v_composite_child_weights AS
-- 分支 A: COMPOSITE 父级 → 所有子件 unit_weight
SELECT
    asy.hf_part_no                          AS hf_part_no,
    COALESCE(asy.child_part_no, asy.input_material_no)  AS child_hf_part_no,
    COALESCE(mp_child.part_name, asy.input_material_name,
             asy.child_part_no, asy.input_material_no)  AS child_part_name,
    asy.seq_no                              AS child_seq,
    mp_child.unit_weight                    AS unit_weight,
    'g'                                     AS unit_label
FROM mat_bom asy
LEFT JOIN mat_part mp_child
       ON mp_child.part_no = COALESCE(asy.child_part_no, asy.input_material_no)
WHERE asy.bom_type = 'ASSEMBLY'

UNION ALL

-- 分支 B: SIMPLE 自身 → 直接拿 mat_part.unit_weight
SELECT
    mp.part_no                              AS hf_part_no,
    mp.part_no                              AS child_hf_part_no,
    COALESCE(mp.part_name, mp.part_no)      AS child_part_name,
    0                                       AS child_seq,
    mp.unit_weight                          AS unit_weight,
    'g'                                     AS unit_label
FROM mat_part mp
WHERE NOT EXISTS (
    SELECT 1 FROM mat_bom WHERE hf_part_no = mp.part_no AND bom_type = 'ASSEMBLY'
);

COMMENT ON VIEW v_composite_child_weights IS
    'V194/V196/V202: 产品单重明细 — A) 有 ASSEMBLY 行 → 子件单重聚合 / B) 无 ASSEMBLY 行 → 自身 unit_weight';

-- ── 自检 ────────────────────────────────────────────────────────────────────
DO $$
DECLARE
    v_simple_test_part TEXT := 'CFG-AgCu-000009';
    v_composite_test_part TEXT := 'CFG-COMBO-000018';
    v_mat_rows_simple INT;
    v_mat_rows_composite INT;
    v_elem_rows_simple INT;
    v_proc_rows_simple INT;
    v_weight_rows_simple INT;
BEGIN
    -- SIMPLE 产品: 4 个视图每个应返 >=1 行 (前提是该产品在 mat_part 有行)
    IF EXISTS (SELECT 1 FROM mat_part WHERE part_no = v_simple_test_part) THEN
        SELECT COUNT(*) INTO v_mat_rows_simple
            FROM v_composite_child_materials WHERE hf_part_no = v_simple_test_part;
        SELECT COUNT(*) INTO v_elem_rows_simple
            FROM v_composite_child_elements WHERE hf_part_no = v_simple_test_part;
        SELECT COUNT(*) INTO v_proc_rows_simple
            FROM v_composite_child_processes WHERE hf_part_no = v_simple_test_part;
        SELECT COUNT(*) INTO v_weight_rows_simple
            FROM v_composite_child_weights WHERE hf_part_no = v_simple_test_part;
        RAISE NOTICE 'V202 自检: SIMPLE % → mat=%, elem=%, proc=%, weight=%',
            v_simple_test_part, v_mat_rows_simple, v_elem_rows_simple, v_proc_rows_simple, v_weight_rows_simple;
        IF v_mat_rows_simple = 0 THEN
            RAISE NOTICE 'V202 提醒: SIMPLE % 在 v_composite_child_materials 仍 0 行 — 检查 mat_part.material_recipe_id', v_simple_test_part;
        END IF;
    ELSE
        RAISE NOTICE 'V202 自检跳过: % 不在 mat_part 表', v_simple_test_part;
    END IF;

    -- COMPOSITE 产品: 视图应继续工作 (V196 原行为)
    IF EXISTS (SELECT 1 FROM mat_part WHERE part_no = v_composite_test_part) THEN
        SELECT COUNT(*) INTO v_mat_rows_composite
            FROM v_composite_child_materials WHERE hf_part_no = v_composite_test_part;
        RAISE NOTICE 'V202 自检: COMPOSITE % → mat=% 行 (子件聚合)',
            v_composite_test_part, v_mat_rows_composite;
    END IF;

    RAISE NOTICE 'V202 完成: 4 视图扩 UNION SIMPLE 分支. 用户须强刷前端 + Quarkus 重启清缓存';
END $$;
