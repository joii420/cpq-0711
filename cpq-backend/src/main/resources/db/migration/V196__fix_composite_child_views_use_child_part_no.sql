-- V196: 修 V194 视图 JOIN 列错误 — 子件 hfPartNo 在 mat_bom.child_part_no 而非 input_material_no
--
-- 故障: V194 创建 4 个 v_composite_child_* 视图时, ASSEMBLY 行的子件 hfPartNo 假设存
-- input_material_no 列, 但 V168 起 mat_bom 加了独立 child_part_no 列专门给 COMPOSITE
-- 用 — input_material_no 留给 ELEMENT 行的元素代码 / SIMPLE 的料号映射. 结果 V194
-- 视图 JOIN 不到子件 → child_hf_part_no NULL → 4 个 Tab 显示加载中.
--
-- 修法: 改 LEFT JOIN ON 子句用 child_part_no, 兼容老数据用 COALESCE 兜底.

DROP VIEW IF EXISTS v_composite_child_materials;
DROP VIEW IF EXISTS v_composite_child_elements;
DROP VIEW IF EXISTS v_composite_child_processes;
DROP VIEW IF EXISTS v_composite_child_weights;

-- ── 1. v_composite_child_materials ───────────────────────────────────────────
CREATE VIEW v_composite_child_materials AS
SELECT
    asy.hf_part_no                       AS hf_part_no,
    COALESCE(asy.child_part_no, asy.input_material_no)  AS child_hf_part_no,
    COALESCE(mp_child.part_name, asy.input_material_name,
             asy.child_part_no, asy.input_material_no)   AS child_part_name,
    asy.seq_no                           AS child_seq,
    mp_child.material_recipe_id          AS recipe_id,
    mr.code                              AS material_code,
    mr.symbol                            AS chemical_symbol,
    mr.name                              AS material_name,
    mr.spec_label                        AS spec_label,
    mr.recipe_type                       AS recipe_type
FROM mat_bom asy
LEFT JOIN mat_part mp_child
       ON mp_child.part_no = COALESCE(asy.child_part_no, asy.input_material_no)
LEFT JOIN material_recipe mr ON mr.id = mp_child.material_recipe_id
WHERE asy.bom_type = 'ASSEMBLY';
COMMENT ON VIEW v_composite_child_materials IS
    'V194/V196: 选配组合产品父级 hfPartNo 聚合子件材质; 子件 JOIN 用 child_part_no (V168 后) 兼容 input_material_no 老数据';

-- ── 2. v_composite_child_elements ───────────────────────────────────────────
CREATE VIEW v_composite_child_elements AS
SELECT
    asy.hf_part_no                       AS hf_part_no,
    COALESCE(asy.child_part_no, asy.input_material_no)  AS child_hf_part_no,
    COALESCE(mp_child.part_name, asy.input_material_name,
             asy.child_part_no, asy.input_material_no)   AS child_part_name,
    asy.seq_no                           AS child_seq,
    elem.seq_no                          AS seq_no,
    elem.element_name                    AS element_name,
    elem.composition_pct                 AS composition_pct
FROM mat_bom asy
JOIN mat_bom elem ON elem.hf_part_no = COALESCE(asy.child_part_no, asy.input_material_no)
                  AND elem.bom_type = 'ELEMENT'
LEFT JOIN mat_part mp_child
       ON mp_child.part_no = COALESCE(asy.child_part_no, asy.input_material_no)
WHERE asy.bom_type = 'ASSEMBLY';
COMMENT ON VIEW v_composite_child_elements IS
    'V194/V196: 父级 hfPartNo 聚合所有子件 ELEMENT 行';

-- ── 3. v_composite_child_processes ──────────────────────────────────────────
CREATE VIEW v_composite_child_processes AS
SELECT
    asy.hf_part_no                       AS hf_part_no,
    COALESCE(asy.child_part_no, asy.input_material_no)  AS child_hf_part_no,
    COALESCE(mp_child.part_name, asy.input_material_name,
             asy.child_part_no, asy.input_material_no)   AS child_part_name,
    asy.seq_no                           AS child_seq,
    proc.seq_no                          AS seq_no,
    proc.process_code                    AS process_code,
    proc.assembly_process                AS assembly_process,
    proc.customer_id                     AS customer_id
FROM mat_bom asy
JOIN mat_process proc ON proc.hf_part_no = COALESCE(asy.child_part_no, asy.input_material_no)
                      AND proc.is_current = true
LEFT JOIN mat_part mp_child
       ON mp_child.part_no = COALESCE(asy.child_part_no, asy.input_material_no)
WHERE asy.bom_type = 'ASSEMBLY';
COMMENT ON VIEW v_composite_child_processes IS
    'V194/V196: 父级 hfPartNo 聚合所有子件 mat_process 行';

-- ── 4. v_composite_child_weights ────────────────────────────────────────────
CREATE VIEW v_composite_child_weights AS
SELECT
    asy.hf_part_no                       AS hf_part_no,
    COALESCE(asy.child_part_no, asy.input_material_no)  AS child_hf_part_no,
    COALESCE(mp_child.part_name, asy.input_material_name,
             asy.child_part_no, asy.input_material_no)   AS child_part_name,
    asy.seq_no                           AS child_seq,
    mp_child.unit_weight                 AS unit_weight,
    'g'                                  AS unit_label
FROM mat_bom asy
LEFT JOIN mat_part mp_child
       ON mp_child.part_no = COALESCE(asy.child_part_no, asy.input_material_no)
WHERE asy.bom_type = 'ASSEMBLY';
COMMENT ON VIEW v_composite_child_weights IS
    'V194/V196: 父级 hfPartNo 聚合所有子件单重';

-- ── 自检 ────────────────────────────────────────────────────────────────────
DO $$
DECLARE
    v_view_count INT;
    v_test_mat INT;
BEGIN
    SELECT COUNT(*) INTO v_view_count FROM information_schema.views
        WHERE table_schema = 'public'
          AND table_name IN (
            'v_composite_child_materials','v_composite_child_elements',
            'v_composite_child_processes','v_composite_child_weights');
    -- 验证 CFG-COMBO-000001 (历史 COMPOSITE) 在视图能查到非 NULL child_hf_part_no
    SELECT COUNT(*) INTO v_test_mat FROM v_composite_child_materials
        WHERE hf_part_no = 'CFG-COMBO-000001' AND child_hf_part_no IS NOT NULL;
    IF v_view_count <> 4 THEN
        RAISE EXCEPTION 'V196 自检失败: 视图重建 % 个 != 4', v_view_count;
    END IF;
    IF v_test_mat = 0 THEN
        RAISE NOTICE 'V196 注意: CFG-COMBO-000001 在 v_composite_child_materials 仍 0 行非 NULL — 可能 mat_bom ASSEMBLY 行也没 child_part_no, 需要业务侧补数据';
    ELSE
        RAISE NOTICE 'V196 自检通过: 4 个视图重建, CFG-COMBO-000001 命中 % 条子件材质行', v_test_mat;
    END IF;
END $$;
