-- ============================================================
-- V160: 给所有 v_q_*_merged 视图暴露 part_version 列
-- ============================================================
-- 根因:
--   V128/V133/V135/V136/V137/V141 创建/重建的 6 个 quotation 合并视图,
--   SELECT 投影均未包含 part_version 列. 当 V153 给 mat_bom/mat_fee/
--   mat_process/mat_plating_fee/mat_plating_plan 加上 part_version 后,
--   视图层因列结构不变, ImplicitJoinRewriter.getColumns 拿到的视图列
--   集合不含 'part_version' → tableCols.contains("part_version")=false
--   → 跳过 AND part_version=N 谓词注入 → 视图查询返回所有版本叠加
--   → 用户在产品卡片 "元素" / "来料" / "成品" 等 tab 看到 v2000 + v2001
--     的 BOM 行同时出现 (本次报告的 Bug).
--
--   ELEMENT_RECYCLE / INCOMING_FIXED 等 mat_fee 分支因有 is_current=true
--   兜底, 显示为单版本; 但 mat_bom 分支无此保护, 必然多版本叠加.
--
-- 实施:
--   DROP CASCADE 6 个视图, 重建时每个 SELECT 分支末尾追加 part_version 列:
--     - mat_bom / mat_fee / mat_process 分支: 直接 SELECT 该表的 part_version
--     - mat_plating_fee LEFT JOIN mat_plating_plan: 取 fee 表的 part_version
--       (plan 字段跟着 FEE 行展示, Q2=C 在 V141 结构下已天然满足)
--
--   视图列结构变化, ImplicitJoinRewriter 内的 tableColumnsCache + Quarkus
--   CachedSqlCompiler 均需清理 → 必须 touch 一个 java 文件重启 Quarkus.
--
-- 不动:
--   v_q_part_info_merged: 底表 (mat_customer_part_mapping / mat_part /
--     exchange_rate) 均非版本化表 (per PartVersionService.VERSIONED_TABLES),
--     不应按 part_version 过滤 — 料号属性与汇率本就跨版本共享.
--
-- 自检:
--   DDL 落库后, 用 BNF path 重新查 v_q_element_merged.element_name 等字段,
--   只应返当前 part_version_locked 对应那一版本的 4 行 ELEMENT (而非 8 行).
-- ============================================================

-- ── DROP CASCADE 6 视图 (顺序无关, 互不依赖) ─────────────────────
DROP VIEW IF EXISTS v_q_incoming_merged CASCADE;
DROP VIEW IF EXISTS v_q_element_merged  CASCADE;
DROP VIEW IF EXISTS v_q_finished_merged CASCADE;
DROP VIEW IF EXISTS v_q_component_merged CASCADE;
DROP VIEW IF EXISTS v_q_assembly_merged CASCADE;
DROP VIEW IF EXISTS v_q_plating_merged  CASCADE;

-- ════════════════════════════════════════════════════════════════
-- 1. v_q_incoming_merged — 来料 (V133 基线 + part_version)
-- ════════════════════════════════════════════════════════════════
CREATE VIEW v_q_incoming_merged AS
-- 来源 1: 来料 BOM
SELECT
    'BOM'::VARCHAR               AS source_type,
    hf_part_no,
    seq_no,
    input_material_no,
    input_material_name,
    output_material_type,
    gross_qty,
    net_qty,
    gross_unit                   AS weight_unit,
    CAST(loss_rate   * 100 AS NUMERIC(10,4)) AS loss_rate,
    CAST(defect_rate * 100 AS NUMERIC(10,4)) AS defect_rate,
    NULL::INT                    AS sub_seq_no,
    NULL::VARCHAR(128)           AS element_name,
    NULL::DECIMAL(18,4)          AS fee_value,
    NULL::DECIMAL(10,4)          AS fee_ratio,
    NULL::VARCHAR(8)             AS currency,
    NULL::VARCHAR(16)            AS price_unit,
    NULL::BOOLEAN                AS price_floating,
    NULL::DECIMAL(10,4)          AS settlement_rise_ratio,
    NULL::DECIMAL(18,4)          AS fixed_rise_value,
    NULL::VARCHAR(8)             AS rise_currency,
    NULL::VARCHAR(16)            AS rise_unit,
    NULL::DECIMAL(10,4)          AS recycle_pct,
    part_version                                            -- V160
FROM mat_bom
WHERE bom_type = 'INCOMING'

UNION ALL

-- 来源 2: 来料固定加工费
SELECT
    'INCOMING_FIXED'::VARCHAR    AS source_type,
    hf_part_no,
    seq_no,
    dim_input_material_no        AS input_material_no,
    dim_input_material_name      AS input_material_name,
    NULL::VARCHAR(64)            AS output_material_type,
    NULL::DECIMAL(18,4)          AS gross_qty,
    NULL::DECIMAL(18,4)          AS net_qty,
    NULL::VARCHAR(16)            AS weight_unit,
    NULL::DECIMAL(10,4)          AS loss_rate,
    NULL::DECIMAL(10,4)          AS defect_rate,
    NULL::INT                    AS sub_seq_no,
    NULL::VARCHAR(128)           AS element_name,
    fee_value,
    CAST(fee_ratio              * 100 AS NUMERIC(10,4)) AS fee_ratio,
    currency,
    price_unit,
    price_floating,
    CAST(settlement_rise_ratio  * 100 AS NUMERIC(10,4)) AS settlement_rise_ratio,
    fixed_rise_value,
    rise_currency,
    rise_unit,
    NULL::DECIMAL(10,4)          AS recycle_pct,
    part_version                                            -- V160
FROM mat_fee
WHERE fee_type = 'INCOMING_FIXED'
  AND is_current = true

UNION ALL

-- 来源 3: 来料其他费用
SELECT
    'INCOMING_OTHER'::VARCHAR    AS source_type,
    hf_part_no,
    seq_no,
    dim_input_material_no        AS input_material_no,
    dim_input_material_name      AS input_material_name,
    NULL::VARCHAR(64)            AS output_material_type,
    NULL::DECIMAL(18,4)          AS gross_qty,
    NULL::DECIMAL(18,4)          AS net_qty,
    NULL::VARCHAR(16)            AS weight_unit,
    NULL::DECIMAL(10,4)          AS loss_rate,
    NULL::DECIMAL(10,4)          AS defect_rate,
    dim_sub_seq_no               AS sub_seq_no,
    dim_element_name             AS element_name,
    fee_value,
    CAST(fee_ratio * 100 AS NUMERIC(10,4)) AS fee_ratio,
    currency,
    price_unit,
    NULL::BOOLEAN                AS price_floating,
    NULL::DECIMAL(10,4)          AS settlement_rise_ratio,
    NULL::DECIMAL(18,4)          AS fixed_rise_value,
    NULL::VARCHAR(8)             AS rise_currency,
    NULL::VARCHAR(16)            AS rise_unit,
    NULL::DECIMAL(10,4)          AS recycle_pct,
    part_version                                            -- V160
FROM mat_fee
WHERE fee_type = 'INCOMING_OTHER'
  AND is_current = true

UNION ALL

-- 来源 4: 来料回收折扣
SELECT
    'MATERIAL_RECYCLE'::VARCHAR  AS source_type,
    hf_part_no,
    seq_no,
    dim_input_material_no        AS input_material_no,
    dim_input_material_name      AS input_material_name,
    NULL::VARCHAR(64)            AS output_material_type,
    NULL::DECIMAL(18,4)          AS gross_qty,
    NULL::DECIMAL(18,4)          AS net_qty,
    NULL::VARCHAR(16)            AS weight_unit,
    NULL::DECIMAL(10,4)          AS loss_rate,
    NULL::DECIMAL(10,4)          AS defect_rate,
    NULL::INT                    AS sub_seq_no,
    NULL::VARCHAR(128)           AS element_name,
    NULL::DECIMAL(18,4)          AS fee_value,
    NULL::DECIMAL(10,4)          AS fee_ratio,
    NULL::VARCHAR(8)             AS currency,
    NULL::VARCHAR(16)            AS price_unit,
    NULL::BOOLEAN                AS price_floating,
    NULL::DECIMAL(10,4)          AS settlement_rise_ratio,
    NULL::DECIMAL(18,4)          AS fixed_rise_value,
    NULL::VARCHAR(8)             AS rise_currency,
    NULL::VARCHAR(16)            AS rise_unit,
    CAST(fee_ratio * 100 AS NUMERIC(10,4)) AS recycle_pct,
    part_version                                            -- V160
FROM mat_fee
WHERE fee_type = 'MATERIAL_RECYCLE'
  AND is_current = true;

COMMENT ON VIEW v_q_incoming_merged IS
    'V128+V133+V160: 来料合并视图 — 已暴露 part_version, 让 ImplicitJoinRewriter 注入 AND part_version=N';

-- ════════════════════════════════════════════════════════════════
-- 2. v_q_element_merged — 元素 (V135 基线 + part_version)
-- ════════════════════════════════════════════════════════════════
CREATE VIEW v_q_element_merged AS
-- 来源 1: 元素 BOM
SELECT
    'BOM'::VARCHAR               AS source_type,
    hf_part_no,
    input_material_no,
    input_material_name,
    seq_no,
    element_name,
    composition_pct,                                           -- 整数百分比直存
    CAST(loss_rate * 100 AS NUMERIC(10,4)) AS loss_rate,
    gross_qty,
    gross_unit,
    net_qty,
    net_unit,
    NULL::DECIMAL(10,4)          AS recycle_pct,
    part_version                                               -- V160
FROM mat_bom
WHERE bom_type = 'ELEMENT'

UNION ALL

-- 来源 2: 元素回收折扣
SELECT
    'ELEMENT_RECYCLE'::VARCHAR   AS source_type,
    hf_part_no,
    dim_input_material_no        AS input_material_no,
    dim_input_material_name      AS input_material_name,
    seq_no,
    dim_element_name             AS element_name,
    NULL::DECIMAL(10,4)          AS composition_pct,
    NULL::DECIMAL(10,4)          AS loss_rate,
    NULL::DECIMAL(18,4)          AS gross_qty,
    NULL::VARCHAR(16)            AS gross_unit,
    NULL::DECIMAL(18,4)          AS net_qty,
    NULL::VARCHAR(16)            AS net_unit,
    CAST(fee_ratio * 100 AS NUMERIC(10,4)) AS recycle_pct,
    part_version                                               -- V160
FROM mat_fee
WHERE fee_type = 'ELEMENT_RECYCLE'
  AND is_current = true;

COMMENT ON VIEW v_q_element_merged IS
    'V128+V133+V135+V160: 元素合并视图 — 已暴露 part_version';

-- ════════════════════════════════════════════════════════════════
-- 3. v_q_finished_merged — 成品 (V128 基线 + part_version)
-- ════════════════════════════════════════════════════════════════
CREATE VIEW v_q_finished_merged AS
-- 来源 1: 成品固定加工费
SELECT
    'FINISHED_FIXED'::VARCHAR    AS source_type,
    hf_part_no,
    seq_no,
    NULL::VARCHAR(128)           AS element_name,
    fee_value,
    fee_ratio,
    currency,
    price_unit,
    part_version                                               -- V160
FROM mat_fee
WHERE fee_type = 'FINISHED_FIXED'
  AND is_current = true

UNION ALL

-- 来源 2: 成品其他费用
SELECT
    'FINISHED_OTHER'::VARCHAR    AS source_type,
    hf_part_no,
    seq_no,
    dim_element_name             AS element_name,
    fee_value,
    fee_ratio,
    currency,
    price_unit,
    part_version                                               -- V160
FROM mat_fee
WHERE fee_type = 'FINISHED_OTHER'
  AND is_current = true;

COMMENT ON VIEW v_q_finished_merged IS
    'V128+V160: 成品合并视图 — 已暴露 part_version';

-- ════════════════════════════════════════════════════════════════
-- 4. v_q_component_merged — 组成件 (V128 基线 + part_version)
-- ════════════════════════════════════════════════════════════════
CREATE VIEW v_q_component_merged AS
SELECT
    'COMPONENT_BOM'::VARCHAR     AS source_type,
    hf_part_no,
    seq_no,
    process_code,
    assembly_process,
    sub_seq_no,
    component_part_no,
    component_name,
    supplier_code,
    supplier_name,
    quantity,
    quantity_unit,
    unit_price,
    freight,
    currency,
    price_unit,
    part_version                                               -- V160
FROM mat_process
WHERE is_current = true;

COMMENT ON VIEW v_q_component_merged IS
    'V128+V160: 组成件合并视图 — 已暴露 part_version';

-- ════════════════════════════════════════════════════════════════
-- 5. v_q_assembly_merged — 组装加工 (V136 基线 + part_version)
-- ════════════════════════════════════════════════════════════════
CREATE VIEW v_q_assembly_merged AS
SELECT
    'ASSEMBLY_PROCESS'::VARCHAR              AS source_type,
    hf_part_no,
    seq_no,
    dim_assembly_process                     AS assembly_process,
    fee_value,
    currency,
    price_unit,
    CAST(reject_rate * 100 AS NUMERIC(10,4)) AS reject_rate,
    part_version                                               -- V160
FROM mat_fee
WHERE fee_type = 'ASSEMBLY_PROCESS'
  AND is_current = true;

COMMENT ON VIEW v_q_assembly_merged IS
    'V128+V136+V160: 组装加工合并视图 — 已暴露 part_version';

-- ════════════════════════════════════════════════════════════════
-- 6. v_q_plating_merged — 电镀 (V141 基线 + part_version)
--    Q2=C: plating_plan 信息通过 V141 LEFT JOIN 融进 FEE 行,
--    不再有独立 PLAN 分支, part_version 取自 mat_plating_fee.
-- ════════════════════════════════════════════════════════════════
CREATE VIEW v_q_plating_merged AS
SELECT
    'FEE'::VARCHAR                            AS source_type,
    f.hf_part_no,
    f.plating_plan_code                       AS plan_code,
    f.plan_version,
    p.seq_no,
    p.plating_element,
    p.plating_area,
    p.coating_thickness,
    p.plating_requirement,
    f.plating_process_fee,
    f.plating_material_fee,
    f.currency,
    f.price_unit,
    CAST(f.defect_rate * 100 AS NUMERIC(10,4)) AS defect_rate,
    f.part_version                                             -- V160
FROM mat_plating_fee f
LEFT JOIN mat_plating_plan p
       ON p.plan_code = f.plating_plan_code
      AND p.version   = f.plan_version
WHERE f.is_current = true;

COMMENT ON VIEW v_q_plating_merged IS
    'V128+V133+V137+V141+V160: 电镀费用 LEFT JOIN 电镀方案 — 已暴露 part_version (取 mat_plating_fee 侧)';

-- ════════════════════════════════════════════════════════════════
-- 完成自检通知
-- ════════════════════════════════════════════════════════════════
DO $$
DECLARE
    v_cnt_incoming INT;
    v_cnt_element  INT;
    v_cnt_finished INT;
    v_cnt_comp     INT;
    v_cnt_assembly INT;
    v_cnt_plating  INT;
BEGIN
    SELECT COUNT(*) INTO v_cnt_incoming FROM v_q_incoming_merged;
    SELECT COUNT(*) INTO v_cnt_element  FROM v_q_element_merged;
    SELECT COUNT(*) INTO v_cnt_finished FROM v_q_finished_merged;
    SELECT COUNT(*) INTO v_cnt_comp     FROM v_q_component_merged;
    SELECT COUNT(*) INTO v_cnt_assembly FROM v_q_assembly_merged;
    SELECT COUNT(*) INTO v_cnt_plating  FROM v_q_plating_merged;
    RAISE NOTICE '━━━━ V160 自检报告 ━━━━━━━━━━━━━━━━━━━━━━━━━━━━';
    RAISE NOTICE '  v_q_incoming_merged   行数 = % (新增 part_version 列)', v_cnt_incoming;
    RAISE NOTICE '  v_q_element_merged    行数 = % (新增 part_version 列)', v_cnt_element;
    RAISE NOTICE '  v_q_finished_merged   行数 = % (新增 part_version 列)', v_cnt_finished;
    RAISE NOTICE '  v_q_component_merged  行数 = % (新增 part_version 列)', v_cnt_comp;
    RAISE NOTICE '  v_q_assembly_merged   行数 = % (新增 part_version 列)', v_cnt_assembly;
    RAISE NOTICE '  v_q_plating_merged    行数 = % (新增 part_version 列)', v_cnt_plating;
    RAISE NOTICE '━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━';
    RAISE NOTICE 'V160: 必须 touch 一个 java 文件触发 Quarkus dev 重启';
    RAISE NOTICE 'V160: 清理 ImplicitJoinRewriter.tableColumnsCache + CachedSqlCompiler 进程缓存';
END $$;
