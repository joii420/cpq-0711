-- V137: v_q_plating_merged 切换数据源到 V125 新表
--
-- 起因: V128 创建视图时仍用 V125 已废弃的 plating_plan / plating_fee 旧表,
--       但 V5 import 在 V125 后只写入新表 mat_plating_plan / mat_plating_fee
--       (V125 仅做了一次性数据 mirror, 后续 import 不再双写). 结果:
--       - V125 之前已有的料号 (如 3120012574) — 旧表有 mirror, 视图能查到
--       - V125 之后新 import 的料号 (如 3120012577) — 只写新表, 视图查不到 → 电镀 tab 空
--
-- 实施: DROP CASCADE + 重建. 来源 1 改 plating_plan → mat_plating_plan;
--       来源 2 改 plating_fee → mat_plating_fee. 列结构和 V133 完全一致.
--       (mat_plating_fee 含 customer_id 列, ImplicitJoinRewriter 会在 BNF path 求值时
--        按当前报价单 customer_id 自动注入谓词, 视图本身不需要输出 customer_id 列.)
--
-- DDL 后必须 touch java 强制 Quarkus 重启 (CLAUDE.md 强调; ImplicitJoinRewriter
-- tableColumnsCache / CachedSqlCompiler 都是进程级缓存, 视图列结构变化需清).

DROP VIEW IF EXISTS v_q_plating_merged CASCADE;

CREATE VIEW v_q_plating_merged AS
-- 来源 1: 电镀方案 (V125 新表 mat_plating_plan; 全局表, hf_part_no 为 NULL)
SELECT
    'PLAN'::VARCHAR              AS source_type,
    NULL::VARCHAR(64)            AS hf_part_no,
    plan_code,
    version                      AS plan_version,
    seq_no,
    plating_element,
    plating_area,
    coating_thickness,
    plating_requirement,
    NULL::DECIMAL(18,4)          AS plating_process_fee,
    NULL::DECIMAL(18,4)          AS plating_material_fee,
    NULL::VARCHAR(8)             AS currency,
    NULL::VARCHAR(16)            AS price_unit,
    NULL::DECIMAL(10,4)          AS defect_rate
FROM mat_plating_plan

UNION ALL

-- 来源 2: 电镀费用 (V125 新表 mat_plating_fee; defect_rate ×100 — V133 已修语义)
SELECT
    'FEE'::VARCHAR               AS source_type,
    hf_part_no,
    plating_plan_code            AS plan_code,
    plan_version,
    NULL::INT                    AS seq_no,
    NULL::VARCHAR(64)            AS plating_element,
    NULL::DECIMAL(18,4)          AS plating_area,
    NULL::DECIMAL(10,4)          AS coating_thickness,
    NULL::VARCHAR(256)           AS plating_requirement,
    plating_process_fee,
    plating_material_fee,
    currency,
    price_unit,
    CAST(defect_rate * 100 AS NUMERIC(10,4)) AS defect_rate
FROM mat_plating_fee
WHERE is_current = true;

COMMENT ON VIEW v_q_plating_merged IS
    'V128+V133+V137: 电镀合并视图 - 数据源切到 V125 新表 (mat_plating_plan + mat_plating_fee), defect_rate 百分比 x100';

DO $$ BEGIN RAISE NOTICE 'V137: v_q_plating_merged 切到新表 (mat_plating_plan + mat_plating_fee) 完成'; END $$;
