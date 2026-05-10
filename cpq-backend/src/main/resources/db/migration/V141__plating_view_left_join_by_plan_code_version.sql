-- V141: v_q_plating_merged 改为 LEFT JOIN by (plan_code, version)
--
-- 起因: V128/V137 视图是 UNION ALL (PLAN 行 + FEE 行各自独立). 但
--       PLAN 行 hf_part_no=NULL (mat_plating_plan 是全局表), 当 ImplicitJoinRewriter
--       按当前报价单 hf_part_no 加谓词时 PLAN 行被过滤掉, 导致前端电镀 tab 只显示
--       FEE 行, 缺失"电镀元素名称/电镀面积/镀层厚度/电镀要求"等方案侧字段.
--
-- 用户预期 (实测确认): 应当按 (hf_part_no + plating_plan_code + plan_version) 匹配
--                     mat_plating_plan, 把方案多元素行附加到 FEE 行展示
--                     (1 个 FEE × N 个方案元素 = N 行).
--
-- 实施: DROP CASCADE + 重建为 LEFT JOIN. 视图列名保持不变 (前端 BNF path 引用稳定).
--       defect_rate 仍 ×100 (V133/V137 已修, 保持百分比显示语义).
--       plating_area / coating_thickness 是绝对数值, 不 ×100.
--
-- DDL 后必须 touch java 强制 Quarkus 重启 (CLAUDE.md, 视图列结构变更需清缓存).

DROP VIEW IF EXISTS v_q_plating_merged CASCADE;

CREATE VIEW v_q_plating_merged AS
SELECT
    'FEE'::VARCHAR                            AS source_type,
    f.hf_part_no,
    f.plating_plan_code                       AS plan_code,
    f.plan_version,
    p.seq_no,                                              -- 来自 PLAN (mat_plating_plan)
    p.plating_element,                                     -- 来自 PLAN
    p.plating_area,                                        -- 来自 PLAN (cm2, 绝对数值)
    p.coating_thickness,                                   -- 来自 PLAN (um, 绝对数值)
    p.plating_requirement,                                 -- 来自 PLAN
    f.plating_process_fee,                                 -- 来自 FEE
    f.plating_material_fee,                                -- 来自 FEE
    f.currency,
    f.price_unit,
    CAST(f.defect_rate * 100 AS NUMERIC(10,4))    AS defect_rate
FROM mat_plating_fee f
LEFT JOIN mat_plating_plan p
       ON p.plan_code = f.plating_plan_code
      AND p.version   = f.plan_version
WHERE f.is_current = true;

COMMENT ON VIEW v_q_plating_merged IS
    'V128+V133+V137+V141: 电镀费用 LEFT JOIN 电镀方案 (by plan_code+version) - 让方案多元素行附加到 FEE 行展示, defect_rate ×100';

DO $$
DECLARE v_cnt INT;
BEGIN
    SELECT COUNT(*) INTO v_cnt FROM v_q_plating_merged;
    RAISE NOTICE 'V141: v_q_plating_merged 重建完成 (LEFT JOIN by plan_code+version), 当前行数=%', v_cnt;
END $$;
