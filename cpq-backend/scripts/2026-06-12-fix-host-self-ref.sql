-- 一次性迁移:宿主明细自引用 cross_tab_ref → field token
-- 背景:[宿主.字段] 被存成 source=自身 的 cross_tab_ref → 组件级自环 + 求值取空归0。
-- 规则:type=cross_tab_ref 且 source=本组件 id 且 (agg='NONE' OR agg IS NULL) → {type:field,value:target,label:target}
-- 注:self cross_tab_ref 的 target 天然是明细(小计/总计走 component_subtotal),无需再过滤 detailFields。
-- 自聚合(agg∈SUM/AVG/MAX/MIN/COUNT)不迁移(本期非目标)。

-- 1) 备份(执行前)
CREATE TABLE IF NOT EXISTS _bak_component_formulas_20260612 AS
  SELECT id, formulas, now() AS backed_up_at
  FROM component
  WHERE formulas::text LIKE '%cross_tab_ref%';

-- 2) 迁移
-- 注:expression 为空数组时内层 jsonb_agg 返 NULL,jsonb_set(strict) 会把整条 formula 置 NULL,
--     故用 COALESCE(...,'[]') 兜底保留空数组(当前数据无此情形,纯为重跑健壮性)。
UPDATE component c SET formulas = (
  SELECT jsonb_agg(
    CASE WHEN f ? 'expression' THEN jsonb_set(f, '{expression}', COALESCE((
      SELECT jsonb_agg(
        CASE WHEN tk->>'type' = 'cross_tab_ref'
              AND tk->>'source' = c.id::text
              AND (tk->>'agg' = 'NONE' OR tk->>'agg' IS NULL)
             THEN jsonb_build_object('type','field','value',tk->>'target','label',tk->>'target')
             ELSE tk END
        ORDER BY tk_ord)
      FROM jsonb_array_elements(f->'expression') WITH ORDINALITY e(tk, tk_ord)), '[]'::jsonb))
    ELSE f END
    ORDER BY f_ord)
  FROM jsonb_array_elements(c.formulas) WITH ORDINALITY ff(f, f_ord))
WHERE c.formulas::text LIKE '%cross_tab_ref%';

-- 3) 复查:全库应 0 条"宿主自引用 cross_tab_ref(agg NONE/null)"
SELECT c.id, c.name, count(*) AS remaining_self_loops
FROM component c, jsonb_array_elements(c.formulas) f, jsonb_array_elements(f->'expression') tk
WHERE tk->>'type'='cross_tab_ref'
  AND tk->>'source'=c.id::text
  AND (tk->>'agg'='NONE' OR tk->>'agg' IS NULL)
GROUP BY c.id, c.name;
-- 期望:0 行返回。
