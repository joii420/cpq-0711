-- V136: 补修 V133 漏改的 v_q_assembly_merged.reject_rate 百分比 ×100
--
-- 起因: V133 注释里提到 reject_rate 但 SQL 实际只重建了
--       v_q_incoming_merged / v_q_element_merged / v_q_finished_merged / v_q_plating_merged
--       4 个视图, **遗漏** v_q_assembly_merged. 导致组装加工 tab 「拒收率/不良率(%)」
--       仍显示 0.03 而非 3 (mat_fee.reject_rate 由 toDecimalPercent 入库, 存的是
--       0.03 = 3% 形式的小数, 视图层未 ×100 还原).
--
-- 实施: DROP CASCADE + 重建. reject_rate 列改为 (reject_rate * 100). 其他列原样.
-- DDL 后必须 touch java 强制 Quarkus 重启 (CLAUDE.md 强调; 视图列类型变更需清理
-- ImplicitJoinRewriter / CachedSqlCompiler 进程级缓存).

DROP VIEW IF EXISTS v_q_assembly_merged CASCADE;

CREATE VIEW v_q_assembly_merged AS
SELECT
    'ASSEMBLY_PROCESS'::VARCHAR              AS source_type,
    hf_part_no,
    seq_no,
    dim_assembly_process                     AS assembly_process,
    fee_value,
    currency,
    price_unit,
    CAST(reject_rate * 100 AS NUMERIC(10,4)) AS reject_rate
FROM mat_fee
WHERE fee_type = 'ASSEMBLY_PROCESS'
  AND is_current = true;

COMMENT ON VIEW v_q_assembly_merged IS
    'V128+V136: 组装加工合并视图 - reject_rate 已 x100 显示 (V133 漏修)';

DO $$ BEGIN RAISE NOTICE 'V136: v_q_assembly_merged.reject_rate x100 修复完成'; END $$;
