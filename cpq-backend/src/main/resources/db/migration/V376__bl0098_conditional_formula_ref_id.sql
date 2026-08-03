-- BL-0098 补充：条件公式的规则/默认分支引用也改绑稳定 ID
--
-- 背景：V375 处理的是「字段 → 公式」的普通绑定。条件公式字段走的是另一条路 ——
--   conditional_formula = {rules:[{when, formula}], default}
-- 其中 formula / default 存的是公式**名**。公式一改名，FormulaCalculator.collectFormulaFields
-- 里的 `if (expr != null) rules.add(...)` 就会**静默丢掉**该规则或默认分支。
-- 比普通字段整列不出值更阴险：列还有值，只是悄悄换了分支，钱变了看不出来。
--
-- 受影响的是全库仅有的 3 个条件公式字段，恰恰是最要害的：
--   COMP-0032「物料」(BOM) 材料成本  规则=非银点类材料成本公式 / 默认=银点材料成本公式
--   COMP-0157「物料」(BOM) 材料成本  同上（这两个是 task-0803 的 BOM 验证宿主）
--   COMP-0090「材料成本」  材料成本  规则=公式2 / 默认=公式1
--
-- 本迁移是**纯映射零推断**：这些引用本来就是显式公式名，按名字查出 id 写进去即可。
-- 求值端已改成 formula_id → formulaId → 名字 三级（见 FormulaCalculator.condRefFormula），
-- 存量没补上 id 的（如不迁移的 quotation_view_structure）继续走名字，行为逐位不变。
--
-- 覆盖范围与 V375 一致：component.fields + template.components_snapshot；
-- 不动 quotation_view_structure（13 张老单）与 quotation.submission_snapshot（只读留证）。
--
-- 🚨 空数组陷阱同 V375：内层 jsonb_agg 一律 COALESCE(..., '[]'::jsonb)。

-- ── 1. component.fields[].conditional_formula ─────────────────────────────────
UPDATE component c
SET fields = sub.new_fields
FROM (
  SELECT c2.id,
         COALESCE(jsonb_agg(
           CASE
             WHEN f->>'field_type' = 'FORMULA' AND f ? 'conditional_formula' THEN
               f || jsonb_build_object('conditional_formula',
                    (f->'conditional_formula')
                    -- 1a. rules[] 每条补 formula_id
                    || jsonb_build_object('rules', (
                         SELECT COALESCE(jsonb_agg(
                                  CASE
                                    WHEN COALESCE(r->>'formula_id','') = ''
                                     AND (SELECT fm->>'id' FROM jsonb_array_elements(c2.formulas) fm
                                          WHERE fm->>'name' = r->>'formula' LIMIT 1) IS NOT NULL
                                    THEN r || jsonb_build_object('formula_id',
                                           (SELECT fm->>'id' FROM jsonb_array_elements(c2.formulas) fm
                                            WHERE fm->>'name' = r->>'formula' LIMIT 1))
                                    ELSE r END
                                  ORDER BY ro), '[]'::jsonb)
                         FROM jsonb_array_elements(
                                COALESCE(f->'conditional_formula'->'rules', '[]'::jsonb)
                              ) WITH ORDINALITY AS rr(r, ro)))
                    -- 1b. default 补 default_formula_id
                    || CASE
                         WHEN COALESCE(f->'conditional_formula'->>'default_formula_id','') = ''
                          AND (SELECT fm->>'id' FROM jsonb_array_elements(c2.formulas) fm
                               WHERE fm->>'name' = f->'conditional_formula'->>'default' LIMIT 1) IS NOT NULL
                         THEN jsonb_build_object('default_formula_id',
                                (SELECT fm->>'id' FROM jsonb_array_elements(c2.formulas) fm
                                 WHERE fm->>'name' = f->'conditional_formula'->>'default' LIMIT 1))
                         ELSE '{}'::jsonb
                       END)
             ELSE f END
           ORDER BY ord
         ), '[]'::jsonb) AS new_fields
  FROM component c2, LATERAL jsonb_array_elements(c2.fields) WITH ORDINALITY AS t(f, ord)
  WHERE jsonb_typeof(c2.fields) = 'array' AND jsonb_array_length(c2.fields) > 0
    AND jsonb_typeof(c2.formulas) = 'array'
  GROUP BY c2.id
) sub
WHERE c.id = sub.id;

-- ── 2. template.components_snapshot 里每个 tab 的 fields[].conditional_formula ──
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
                            WHEN f->>'field_type' = 'FORMULA' AND f ? 'conditional_formula' THEN
                              f || jsonb_build_object('conditional_formula',
                                   (f->'conditional_formula')
                                   || jsonb_build_object('rules', (
                                        SELECT COALESCE(jsonb_agg(
                                                 CASE
                                                   WHEN COALESCE(r->>'formula_id','') = ''
                                                    AND (SELECT fm->>'id' FROM jsonb_array_elements(tab->'formulas') fm
                                                         WHERE fm->>'name' = r->>'formula' LIMIT 1) IS NOT NULL
                                                   THEN r || jsonb_build_object('formula_id',
                                                          (SELECT fm->>'id' FROM jsonb_array_elements(tab->'formulas') fm
                                                           WHERE fm->>'name' = r->>'formula' LIMIT 1))
                                                   ELSE r END
                                                 ORDER BY ro), '[]'::jsonb)
                                        FROM jsonb_array_elements(
                                               COALESCE(f->'conditional_formula'->'rules', '[]'::jsonb)
                                             ) WITH ORDINALITY AS rr(r, ro)))
                                   || CASE
                                        WHEN COALESCE(f->'conditional_formula'->>'default_formula_id','') = ''
                                         AND (SELECT fm->>'id' FROM jsonb_array_elements(tab->'formulas') fm
                                              WHERE fm->>'name' = f->'conditional_formula'->>'default' LIMIT 1) IS NOT NULL
                                        THEN jsonb_build_object('default_formula_id',
                                               (SELECT fm->>'id' FROM jsonb_array_elements(tab->'formulas') fm
                                                WHERE fm->>'name' = f->'conditional_formula'->>'default' LIMIT 1))
                                        ELSE '{}'::jsonb
                                      END)
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
