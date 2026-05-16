-- ============================================================================
-- V178: 数值精度扩展 — 让 mat_* / costing_part_* 数值列容纳 Excel 上传的高精度
-- ============================================================================
-- 背景:
--   Excel 「元素BOM」净用量列可达 10 位小数 (0.0566608796),
--   但 mat_bom.net_qty 是 numeric(18,4), 写入时被四舍五入到 4 位 → UI-2
--   「基础数据差异确认」窗口显示 53 条「净用量」差异。
--
-- 方案:
--   - scalar 数量类 (qty/value/fee/weight/area/thickness)  → numeric(20,10)
--   - rate 类 (loss_rate/composition_pct/defect_rate/...)   → numeric(12,8)
--
-- PostgreSQL 不允许直接 ALTER 被视图引用的列类型 → 必须 DROP CASCADE 视图 + 重建
-- 思路: 启动时把全部受影响视图 SELECT 出 pg_views.definition 暂存,
--      DROP CASCADE 后 ALTER 列,再用 EXECUTE 重建视图;
--      重建有依赖顺序问题 → 用重试循环 (失败的等下一轮再试).
-- ============================================================================

BEGIN;

-- ─────────────────────────────────────────────────────────────────────────────
-- 1) 准备阶段: 把所有 v_q_*_merged / v_c_*_merged / v_costing_* / v_*_part_*
--    依赖于待改列的视图 + 它们的下游 (递归), 收集 definition 到临时表
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TEMP TABLE _v178_view_backup (
    seq SERIAL PRIMARY KEY,
    view_name TEXT NOT NULL UNIQUE,
    view_def TEXT NOT NULL
);

-- 递归找所有依赖于 mat_* / costing_part_* 这几个底表的视图 + 其下游视图
WITH RECURSIVE
target_tables(relname) AS (
    VALUES
        ('mat_part'), ('mat_bom'), ('mat_fee'), ('mat_process'),
        ('mat_plating_fee'), ('mat_plating_plan'),
        ('costing_part_design_cost'), ('costing_part_element_bom'),
        ('costing_part_material_bom'), ('costing_part_plating_fee'),
        ('costing_part_quality_check')
),
direct_views AS (
    SELECT DISTINCT dv.oid AS view_oid, dv.relname AS view_name
    FROM pg_depend dep
    JOIN pg_rewrite rw ON dep.objid = rw.oid
    JOIN pg_class dv ON rw.ev_class = dv.oid
    JOIN pg_class src ON dep.refobjid = src.oid
    WHERE src.relname IN (SELECT relname FROM target_tables)
      AND dv.relkind = 'v'
      AND dep.deptype = 'n'
),
cascade_views AS (
    SELECT view_oid, view_name FROM direct_views
    UNION
    SELECT dv2.oid, dv2.relname
    FROM cascade_views cv
    JOIN pg_depend dep ON dep.refobjid = cv.view_oid
    JOIN pg_rewrite rw ON dep.objid = rw.oid
    JOIN pg_class dv2 ON rw.ev_class = dv2.oid
    WHERE dv2.relkind = 'v' AND dep.deptype = 'n' AND dv2.oid <> cv.view_oid
)
INSERT INTO _v178_view_backup(view_name, view_def)
SELECT DISTINCT v.relname, pg_get_viewdef(v.oid, true)
FROM cascade_views cv
JOIN pg_class v ON v.oid = cv.view_oid
WHERE v.relkind = 'v'
ORDER BY v.relname;

DO $$
DECLARE n INT;
BEGIN
    SELECT count(*) INTO n FROM _v178_view_backup;
    RAISE NOTICE 'V178: captured % view definitions for rebuild', n;
END $$;

-- ─────────────────────────────────────────────────────────────────────────────
-- 2) DROP 所有备份的视图 (单次 DROP ... CASCADE 即可清空, 顺序无所谓)
-- ─────────────────────────────────────────────────────────────────────────────

DO $$
DECLARE r RECORD;
BEGIN
    FOR r IN SELECT view_name FROM _v178_view_backup ORDER BY seq DESC LOOP
        EXECUTE 'DROP VIEW IF EXISTS ' || quote_ident(r.view_name) || ' CASCADE';
    END LOOP;
END $$;

-- ─────────────────────────────────────────────────────────────────────────────
-- 3) ALTER COLUMN TYPE: A 段 mat_* 主表 + staging, B 段 costing_part_*
-- ─────────────────────────────────────────────────────────────────────────────

-- A. mat_* 主表
ALTER TABLE mat_part         ALTER COLUMN unit_weight             TYPE numeric(20,10);

ALTER TABLE mat_bom          ALTER COLUMN net_qty                 TYPE numeric(20,10);
ALTER TABLE mat_bom          ALTER COLUMN gross_qty               TYPE numeric(20,10);
ALTER TABLE mat_bom          ALTER COLUMN loss_rate               TYPE numeric(12,8);
ALTER TABLE mat_bom          ALTER COLUMN composition_pct         TYPE numeric(12,8);
ALTER TABLE mat_bom          ALTER COLUMN defect_rate             TYPE numeric(12,8);

ALTER TABLE mat_fee          ALTER COLUMN fee_value               TYPE numeric(20,10);
ALTER TABLE mat_fee          ALTER COLUMN fixed_rise_value        TYPE numeric(20,10);
ALTER TABLE mat_fee          ALTER COLUMN fee_ratio               TYPE numeric(12,8);
ALTER TABLE mat_fee          ALTER COLUMN reject_rate             TYPE numeric(12,8);
ALTER TABLE mat_fee          ALTER COLUMN settlement_rise_ratio   TYPE numeric(12,8);

ALTER TABLE mat_process      ALTER COLUMN unit_price              TYPE numeric(20,10);
ALTER TABLE mat_process      ALTER COLUMN freight                 TYPE numeric(20,10);
ALTER TABLE mat_process      ALTER COLUMN quantity                TYPE numeric(20,10);

ALTER TABLE mat_plating_fee  ALTER COLUMN plating_material_fee    TYPE numeric(20,10);
ALTER TABLE mat_plating_fee  ALTER COLUMN plating_process_fee     TYPE numeric(20,10);
ALTER TABLE mat_plating_fee  ALTER COLUMN defect_rate             TYPE numeric(12,8);

ALTER TABLE mat_plating_plan ALTER COLUMN coating_thickness       TYPE numeric(20,10);
ALTER TABLE mat_plating_plan ALTER COLUMN plating_area            TYPE numeric(20,10);

-- A'. mat_*_staging
ALTER TABLE mat_part_staging         ALTER COLUMN unit_weight             TYPE numeric(20,10);

ALTER TABLE mat_bom_staging          ALTER COLUMN net_qty                 TYPE numeric(20,10);
ALTER TABLE mat_bom_staging          ALTER COLUMN gross_qty               TYPE numeric(20,10);
ALTER TABLE mat_bom_staging          ALTER COLUMN loss_rate               TYPE numeric(12,8);
ALTER TABLE mat_bom_staging          ALTER COLUMN composition_pct         TYPE numeric(12,8);
ALTER TABLE mat_bom_staging          ALTER COLUMN defect_rate             TYPE numeric(12,8);

ALTER TABLE mat_fee_staging          ALTER COLUMN fee_value               TYPE numeric(20,10);
ALTER TABLE mat_fee_staging          ALTER COLUMN fixed_rise_value        TYPE numeric(20,10);
ALTER TABLE mat_fee_staging          ALTER COLUMN fee_ratio               TYPE numeric(12,8);
ALTER TABLE mat_fee_staging          ALTER COLUMN reject_rate             TYPE numeric(12,8);
ALTER TABLE mat_fee_staging          ALTER COLUMN settlement_rise_ratio   TYPE numeric(12,8);

ALTER TABLE mat_process_staging      ALTER COLUMN unit_price              TYPE numeric(20,10);
ALTER TABLE mat_process_staging      ALTER COLUMN freight                 TYPE numeric(20,10);
ALTER TABLE mat_process_staging      ALTER COLUMN quantity                TYPE numeric(20,10);

ALTER TABLE mat_plating_fee_staging  ALTER COLUMN plating_material_fee    TYPE numeric(20,10);
ALTER TABLE mat_plating_fee_staging  ALTER COLUMN plating_process_fee     TYPE numeric(20,10);
ALTER TABLE mat_plating_fee_staging  ALTER COLUMN defect_rate             TYPE numeric(12,8);

ALTER TABLE mat_plating_plan_staging ALTER COLUMN coating_thickness       TYPE numeric(20,10);
ALTER TABLE mat_plating_plan_staging ALTER COLUMN plating_area            TYPE numeric(20,10);

-- B. costing_part_*
ALTER TABLE costing_part_design_cost    ALTER COLUMN design_material_fee TYPE numeric(20,10);
ALTER TABLE costing_part_design_cost    ALTER COLUMN design_proc_fee     TYPE numeric(20,10);
ALTER TABLE costing_part_design_cost    ALTER COLUMN loss_rate           TYPE numeric(12,8);

ALTER TABLE costing_part_element_bom    ALTER COLUMN composition_pct     TYPE numeric(12,8);
ALTER TABLE costing_part_element_bom    ALTER COLUMN loss_rate           TYPE numeric(12,8);

ALTER TABLE costing_part_material_bom   ALTER COLUMN loss_rate           TYPE numeric(12,8);
ALTER TABLE costing_part_material_bom   ALTER COLUMN output_loss_rate    TYPE numeric(12,8);

ALTER TABLE costing_part_plating_fee    ALTER COLUMN plating_material_fee TYPE numeric(20,10);
ALTER TABLE costing_part_plating_fee    ALTER COLUMN plating_process_fee  TYPE numeric(20,10);
ALTER TABLE costing_part_plating_fee    ALTER COLUMN defect_rate          TYPE numeric(12,8);

ALTER TABLE costing_part_quality_check  ALTER COLUMN scrap_rate          TYPE numeric(12,8);

-- ─────────────────────────────────────────────────────────────────────────────
-- 4) 重建视图: 用 "重试循环" 处理依赖顺序 (失败的留到下一轮再试)
-- ─────────────────────────────────────────────────────────────────────────────

DO $$
DECLARE
    iter INT := 0;
    remaining INT;
    last_remaining INT := -1;
    r RECORD;
    rebuilt INT := 0;
BEGIN
    -- 给一个 done 标记列
    ALTER TABLE _v178_view_backup ADD COLUMN done BOOLEAN NOT NULL DEFAULT FALSE;

    LOOP
        iter := iter + 1;
        IF iter > 20 THEN
            RAISE EXCEPTION 'V178 视图重建超过 20 轮仍有 % 个未完成, 可能存在循环依赖或语法错误', remaining;
        END IF;

        SELECT count(*) INTO remaining FROM _v178_view_backup WHERE NOT done;
        EXIT WHEN remaining = 0;

        IF last_remaining = remaining THEN
            -- 这一轮一个都没建成, 抛出最近的错误信息
            FOR r IN SELECT view_name, view_def FROM _v178_view_backup WHERE NOT done ORDER BY seq LIMIT 1 LOOP
                BEGIN
                    EXECUTE 'CREATE VIEW ' || quote_ident(r.view_name) || ' AS ' || r.view_def;
                EXCEPTION WHEN OTHERS THEN
                    RAISE EXCEPTION 'V178 重建视图 % 失败: %', r.view_name, SQLERRM;
                END;
            END LOOP;
        END IF;
        last_remaining := remaining;

        FOR r IN SELECT seq, view_name, view_def FROM _v178_view_backup WHERE NOT done ORDER BY seq LOOP
            BEGIN
                EXECUTE 'CREATE VIEW ' || quote_ident(r.view_name) || ' AS ' || r.view_def;
                UPDATE _v178_view_backup SET done = TRUE WHERE seq = r.seq;
                rebuilt := rebuilt + 1;
            EXCEPTION WHEN OTHERS THEN
                -- 静默跳过,等下一轮 (这一轮可能其它视图先建好,本视图依赖到位)
                NULL;
            END;
        END LOOP;
    END LOOP;

    RAISE NOTICE 'V178: 重建 % 个视图, 用了 % 轮', rebuilt, iter;
END $$;

DROP TABLE _v178_view_backup;

COMMIT;

-- ─────────────────────────────────────────────────────────────────────────────
-- 自检: 验证目标列精度已扩展 (失败抛 EXCEPTION 阻止 Flyway 标记成功)
-- ─────────────────────────────────────────────────────────────────────────────
DO $$
DECLARE
    expected RECORD;
    actual_prec INT;
    actual_scale INT;
    mismatches INT := 0;
    total INT := 0;
BEGIN
    FOR expected IN
        SELECT table_name, column_name, exp_precision, exp_scale
        FROM (VALUES
            ('mat_part',                    'unit_weight',           20, 10),
            ('mat_part_staging',            'unit_weight',           20, 10),
            ('mat_bom',                     'net_qty',               20, 10),
            ('mat_bom',                     'gross_qty',             20, 10),
            ('mat_bom',                     'loss_rate',             12, 8),
            ('mat_bom',                     'composition_pct',       12, 8),
            ('mat_bom',                     'defect_rate',           12, 8),
            ('mat_bom_staging',             'net_qty',               20, 10),
            ('mat_bom_staging',             'gross_qty',             20, 10),
            ('mat_bom_staging',             'loss_rate',             12, 8),
            ('mat_bom_staging',             'composition_pct',       12, 8),
            ('mat_bom_staging',             'defect_rate',           12, 8),
            ('mat_fee',                     'fee_value',             20, 10),
            ('mat_fee',                     'fixed_rise_value',      20, 10),
            ('mat_fee',                     'fee_ratio',             12, 8),
            ('mat_fee',                     'reject_rate',           12, 8),
            ('mat_fee',                     'settlement_rise_ratio', 12, 8),
            ('mat_fee_staging',             'fee_value',             20, 10),
            ('mat_fee_staging',             'fixed_rise_value',      20, 10),
            ('mat_fee_staging',             'fee_ratio',             12, 8),
            ('mat_fee_staging',             'reject_rate',           12, 8),
            ('mat_fee_staging',             'settlement_rise_ratio', 12, 8),
            ('mat_process',                 'unit_price',            20, 10),
            ('mat_process',                 'freight',               20, 10),
            ('mat_process',                 'quantity',              20, 10),
            ('mat_process_staging',         'unit_price',            20, 10),
            ('mat_process_staging',         'freight',               20, 10),
            ('mat_process_staging',         'quantity',              20, 10),
            ('mat_plating_fee',             'plating_material_fee',  20, 10),
            ('mat_plating_fee',             'plating_process_fee',   20, 10),
            ('mat_plating_fee',             'defect_rate',           12, 8),
            ('mat_plating_fee_staging',     'plating_material_fee',  20, 10),
            ('mat_plating_fee_staging',     'plating_process_fee',   20, 10),
            ('mat_plating_fee_staging',     'defect_rate',           12, 8),
            ('mat_plating_plan',            'coating_thickness',     20, 10),
            ('mat_plating_plan',            'plating_area',          20, 10),
            ('mat_plating_plan_staging',    'coating_thickness',     20, 10),
            ('mat_plating_plan_staging',    'plating_area',          20, 10),
            ('costing_part_design_cost',    'design_material_fee',   20, 10),
            ('costing_part_design_cost',    'design_proc_fee',       20, 10),
            ('costing_part_design_cost',    'loss_rate',             12, 8),
            ('costing_part_element_bom',    'composition_pct',       12, 8),
            ('costing_part_element_bom',    'loss_rate',             12, 8),
            ('costing_part_material_bom',   'loss_rate',             12, 8),
            ('costing_part_material_bom',   'output_loss_rate',      12, 8),
            ('costing_part_plating_fee',    'plating_material_fee',  20, 10),
            ('costing_part_plating_fee',    'plating_process_fee',   20, 10),
            ('costing_part_plating_fee',    'defect_rate',           12, 8),
            ('costing_part_quality_check',  'scrap_rate',            12, 8)
        ) AS x(table_name, column_name, exp_precision, exp_scale)
    LOOP
        total := total + 1;
        SELECT numeric_precision, numeric_scale
            INTO actual_prec, actual_scale
            FROM information_schema.columns
            WHERE table_schema = 'public'
              AND table_name = expected.table_name
              AND column_name = expected.column_name;
        IF actual_prec IS NULL THEN
            RAISE WARNING 'V178 自检: 缺列 %.%', expected.table_name, expected.column_name;
            mismatches := mismatches + 1;
        ELSIF actual_prec <> expected.exp_precision OR actual_scale <> expected.exp_scale THEN
            RAISE WARNING 'V178 自检不匹配: %.% 期望 numeric(%,%) 实际 numeric(%,%)',
                expected.table_name, expected.column_name,
                expected.exp_precision, expected.exp_scale,
                actual_prec, actual_scale;
            mismatches := mismatches + 1;
        END IF;
    END LOOP;

    IF mismatches > 0 THEN
        RAISE EXCEPTION 'V178 自检失败: % / % 列精度未达预期', mismatches, total;
    ELSE
        RAISE NOTICE 'V178 自检通过: % 列全部扩展到目标精度', total;
    END IF;
END $$;
