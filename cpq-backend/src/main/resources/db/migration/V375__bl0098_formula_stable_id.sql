-- BL-0098：公式绑定改绑稳定 ID —— 机械迁移part（加 id + 显式绑定翻译，零推断）
--
-- 背景：FORMULA 字段过去靠「按名字 / 按位置猜」找公式，插/删/调序公式会让字段静默换算法，
--       改公式名会让那一列静默不出值。本迁移给公式加不可变 id，字段改绑 id。
--
-- 覆盖范围（2026-08-03 用户裁决 D2）：
--   ✅ component.formulas / component.fields
--   ✅ template.components_snapshot
--   ❌ quotation_view_structure —— 13 张老单不动，靠代码里保留的原 4 级回退兜底（D3）
--   ❌ quotation.submission_snapshot —— 只读留证，从不重新求值
--
-- 本迁移只做机械转换：补 id + 把已有 formula_name 翻译成 formula_id。
-- 隐式绑定（无 formula_name 的字段）由 POST /api/cpq/admin/formula-binding/consolidate 处理，
-- 那里复用 FormulaCalculator 的真实求值口径，SQL 里不重复实现回退链（防口径漂移）。
--
-- 🚨 空数组陷阱（2026-08-03 实测）：库里有 79 个 tab 的 formulas=[]、10 个 tab 的 fields=[]。
--    jsonb_agg 对空集返回 NULL，若不 COALESCE 会把 [] 写成 JSON null，渲染层读到即崩。
--    下方所有内层 jsonb_agg 一律套 COALESCE(..., '[]'::jsonb)。
--
-- 键名风格（实测）：component.fields 与 template.components_snapshot 均为蛇形 field_type
--    （驼峰 fieldType 命中 0）；驼峰只出现在 quotation_view_structure，本迁移不碰它。

-- ── 1. component.formulas[]：缺 id 的补 UUID ──────────────────────────────────
UPDATE component c
SET formulas = sub.new_formulas
FROM (
  SELECT c2.id,
         COALESCE(jsonb_agg(
           CASE WHEN COALESCE(fm->>'id','') = ''
                THEN fm || jsonb_build_object('id', gen_random_uuid()::text)
                ELSE fm END
           ORDER BY ord
         ), '[]'::jsonb) AS new_formulas
  FROM component c2, LATERAL jsonb_array_elements(c2.formulas) WITH ORDINALITY AS t(fm, ord)
  WHERE jsonb_typeof(c2.formulas) = 'array' AND jsonb_array_length(c2.formulas) > 0
  GROUP BY c2.id
) sub
WHERE c.id = sub.id;

-- ── 2. component.fields[]：已有 formula_name → 翻译成 formula_id（纯映射） ──────
--    注：本语句读的 c2.formulas 已是第 1 步补过 id 的版本（同事务顺序执行）。
UPDATE component c
SET fields = sub.new_fields
FROM (
  SELECT c2.id,
         COALESCE(jsonb_agg(
           CASE
             WHEN f->>'field_type' = 'FORMULA'
              AND COALESCE(f->>'formula_name','') <> ''
              AND COALESCE(f->>'formula_id','') = ''
              AND (SELECT fm->>'id' FROM jsonb_array_elements(c2.formulas) fm
                   WHERE fm->>'name' = f->>'formula_name' LIMIT 1) IS NOT NULL
             THEN f || jsonb_build_object('formula_id',
                    (SELECT fm->>'id' FROM jsonb_array_elements(c2.formulas) fm
                     WHERE fm->>'name' = f->>'formula_name' LIMIT 1))
             ELSE f END
           ORDER BY ord
         ), '[]'::jsonb) AS new_fields
  FROM component c2, LATERAL jsonb_array_elements(c2.fields) WITH ORDINALITY AS t(f, ord)
  WHERE jsonb_typeof(c2.fields) = 'array' AND jsonb_array_length(c2.fields) > 0
    AND jsonb_typeof(c2.formulas) = 'array'
  GROUP BY c2.id
) sub
WHERE c.id = sub.id;

-- ── 3a. template.components_snapshot：每个 tab 的 formulas 补 id ────────────────
UPDATE template t
SET components_snapshot = sub.new_snapshot
FROM (
  SELECT t2.id,
         COALESCE(jsonb_agg(
           CASE
             WHEN jsonb_typeof(tab->'formulas') = 'array' THEN
               tab || jsonb_build_object('formulas', (
                    SELECT COALESCE(jsonb_agg(
                             CASE WHEN COALESCE(fm->>'id','') = ''
                                  THEN fm || jsonb_build_object('id', gen_random_uuid()::text)
                                  ELSE fm END
                             ORDER BY fo), '[]'::jsonb)
                    FROM jsonb_array_elements(tab->'formulas') WITH ORDINALITY AS tf(fm, fo)))
             ELSE tab END
           ORDER BY ord
         ), '[]'::jsonb) AS new_snapshot
  FROM template t2, LATERAL jsonb_array_elements(t2.components_snapshot) WITH ORDINALITY AS s(tab, ord)
  WHERE jsonb_typeof(t2.components_snapshot) = 'array'
    AND jsonb_array_length(t2.components_snapshot) > 0
  GROUP BY t2.id
) sub
WHERE t.id = sub.id;

-- ── 3b. template.components_snapshot：每个 tab 的 fields 翻译 formula_name → formula_id ──
UPDATE template t
SET components_snapshot = sub.new_snapshot
FROM (
  SELECT t2.id,
         COALESCE(jsonb_agg(
           CASE
             WHEN jsonb_typeof(tab->'fields') = 'array' AND jsonb_typeof(tab->'formulas') = 'array' THEN
               tab || jsonb_build_object('fields', (
                 SELECT COALESCE(jsonb_agg(
                          CASE
                            WHEN f->>'field_type' = 'FORMULA'
                             AND COALESCE(f->>'formula_name','') <> ''
                             AND COALESCE(f->>'formula_id','') = ''
                             AND (SELECT fm->>'id' FROM jsonb_array_elements(tab->'formulas') fm
                                  WHERE fm->>'name' = f->>'formula_name' LIMIT 1) IS NOT NULL
                            THEN f || jsonb_build_object('formula_id',
                                   (SELECT fm->>'id' FROM jsonb_array_elements(tab->'formulas') fm
                                    WHERE fm->>'name' = f->>'formula_name' LIMIT 1))
                            ELSE f END
                          ORDER BY fo), '[]'::jsonb)
                 FROM jsonb_array_elements(tab->'fields') WITH ORDINALITY AS tf(f, fo)))
             ELSE tab END
           ORDER BY ord
         ), '[]'::jsonb) AS new_snapshot
  FROM template t2, LATERAL jsonb_array_elements(t2.components_snapshot) WITH ORDINALITY AS s(tab, ord)
  WHERE jsonb_typeof(t2.components_snapshot) = 'array'
    AND jsonb_array_length(t2.components_snapshot) > 0
  GROUP BY t2.id
) sub
WHERE t.id = sub.id;
