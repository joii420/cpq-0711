-- V186: 补 process_default_cost 真实 process_code seed + 注册 orphan codes 到 process 主表
--
-- 故障关联 (V184/V185 同源, 由 2026-05-17 QT-20260517-1381 草稿现象暴露):
--   单价列空白 —— V173 PROCESS_DEFAULT_PRICE 只 seed 了 p1~p9 但 mat_process 实际
--   process_code 是 Z350/Z029/MRO-AS-0001~0004 等业务编码 → 查表 100% miss → 列空.
--   等同于 yield 表在 V185 之前的问题, 同源同治.
--
-- 设计取舍:
--   1. 单价默认 seed = 0 (不是 1) — 工序单价的合理默认值因业务而异, 用 0 等同于
--      "未配置"语义, 用户在「全局变量配置」页看到 0 会知道需要去填. 不像成材率
--      seed 100 那样有合理通用默认.
--   2. Z350/Z029 是 mat_process 里实存但 process 主表未注册的 orphan code — 顺手
--      补进 process 主表, name 暂用 process_code 自身兜底, category='MACHINING'
--      (process 表 chk_process_category 限制只能为 SURFACE_TREATMENT/MACHINING/
--       HEAT_TREATMENT/ASSEMBLY/INSPECTION/PACKAGING 之一; 兜底用 MACHINING),
--      status='ACTIVE'. 用户在工序管理页可改 name + 真实 category.
--
-- 落地步骤:
--   1. INSERT mat_process distinct process_code → process_default_cost (默认 0)
--   2. INSERT mat_process orphan process_code → process 主表 (name 兜底)
--   3. 自检

-- ── Step 1: 补 process_default_cost seed (默认 0 = 未配置) ────────────────────
INSERT INTO process_default_cost (process_code, unit_price)
SELECT DISTINCT process_code, 0.0000
FROM mat_process
WHERE process_code IS NOT NULL AND process_code <> ''
ON CONFLICT (process_code) DO NOTHING;

-- ── Step 2: 把 orphan process_code 注册进 process 主表 ────────────────────────
INSERT INTO process (code, name, description, category, sort_order, status)
SELECT
    m.process_code,
    m.process_code,  -- name 用 code 自身兜底, 用户后续改
    'V186 自动注册 — 来自 mat_process 实存但未注册的 orphan code, 请补充名称/分类',
    'MACHINING',  -- chk_process_category 兜底; Z350/Z029 真实分类由用户后续修正
    9999,
    'ACTIVE'
FROM (SELECT DISTINCT process_code FROM mat_process
      WHERE process_code IS NOT NULL AND process_code <> '') AS m
WHERE NOT EXISTS (
    SELECT 1 FROM process p WHERE p.code = m.process_code
);

-- ── Step 3: 自检 ─────────────────────────────────────────────────────────────
DO $$
DECLARE
    v_cost_codes_covered INT;
    v_total_mat_codes INT;
    v_orphan_in_process INT;
BEGIN
    -- mat_process distinct codes 在 process_default_cost 100% 覆盖
    SELECT COUNT(*) INTO v_total_mat_codes
        FROM (SELECT DISTINCT process_code FROM mat_process
              WHERE process_code IS NOT NULL AND process_code <> '') m;
    SELECT COUNT(*) INTO v_cost_codes_covered
        FROM (SELECT DISTINCT process_code FROM mat_process
              WHERE process_code IS NOT NULL AND process_code <> '') AS m
        WHERE EXISTS (SELECT 1 FROM process_default_cost p WHERE p.process_code = m.process_code);
    -- process 主表 100% 覆盖
    SELECT COUNT(*) INTO v_orphan_in_process
        FROM (SELECT DISTINCT process_code FROM mat_process
              WHERE process_code IS NOT NULL AND process_code <> '') AS m
        WHERE EXISTS (SELECT 1 FROM process p WHERE p.code = m.process_code);

    IF v_cost_codes_covered < v_total_mat_codes THEN
        RAISE EXCEPTION 'V186 自检失败: process_default_cost 覆盖 %/% 个 mat_process codes',
            v_cost_codes_covered, v_total_mat_codes;
    END IF;
    IF v_orphan_in_process < v_total_mat_codes THEN
        RAISE EXCEPTION 'V186 自检失败: process 主表 覆盖 %/% 个 mat_process codes',
            v_orphan_in_process, v_total_mat_codes;
    END IF;

    RAISE NOTICE 'V186 自检通过: mat_process distinct codes % 个, process_default_cost 100%% 覆盖, process 主表 100%% 覆盖',
        v_total_mat_codes;
END $$;
