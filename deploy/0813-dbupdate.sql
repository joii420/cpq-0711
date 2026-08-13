-- ============================================================
-- CPQ 内网数据库增量更新 · 2026-08-13
-- ------------------------------------------------------------
-- 适用对象: 已跑过 deploy/0811-dbupdate.sql、表结构停在 Flyway V385 的内网库。
--           本脚本把表结构补齐到 V387(合并覆盖 V386 + V387 两版, 一次跑到位)。
--           若数据库由 2026-08-13 之后版本的 deploy/cpq-init-empty-navicat.sql 新建
--           (基线已是 387), 不需要跑本脚本。
--
-- 覆盖范围: Flyway V386 + V387(task-0813)。基础资料侧 24 张表、86 个数值列(重量族 /
--           含量占比族 / 单价费用族 / 用量数量工时族)精度扩到 numeric(_, 12),
--           整数容量按 new_precision = old_precision - old_scale + 12 保持不变:
--           · auxiliary_energy: 4 列    · capacity: 6 列
--           · electricity_price: 1 列   · element_bom_item: 11 列(含 content, 见下)
--           · element_daily_price: 5 列 · element_price: 1 列
--           · element_price_strategy: 2 列(premium 随 V386; factor 随 V387 —— 定价乘数系数,
--                原始价 × factor + premium, V386 §3.2 勘察遗漏, 事后补裁决纳入)
--           · element_price_version_item: 3 列 · exchange_rate: 1 列
--           · exchange_rate_v6: 2 列    · fee_config: 2 列
--           · labor_rate: 1 列          · material_bom_item: 13 列
--           · material_customer_map: 1 列 · material_master: 1 列
--           · material_recipe_element: 3 列 · packaging_consumable: 1 列
--           · plating_fee: 3 列         · plating_scheme: 5 列
--           · process_master: 1 列      · production_consumable: 1 列
--           · production_energy: 4 列(unit_price 已是 numeric(24,12), 本次不动)
--           · tooling_cost: 4 列        · unit_price: 11 列
--           合计 86 列(V386 85 列 + V387 1 列)。
--
-- ⚠️ 视图特殊处理: element_bom_item.content 被视图 v_composite_child_elements 引用
--           (SELECT 里 ebi.content AS composition_pct), PG 不允许对被视图引用的列直接
--           ALTER TYPE, 报 "cannot alter type of a column used by a view or rule"。
--           本脚本对该列走 DROP VIEW(不加 CASCADE) -> ALTER COLUMN -> 按原定义原样
--           CREATE VIEW。已用 pg_depend 全库扫过: 除该视图外, 本次目标列均不被任何视图/
--           规则引用, 其余 84 列可直接 ALTER。
--           v_composite_child_elements 是 V202 智能视图, 属
--           docs/三大核心模块基线.md §5.1 锁定范围 —— 若你的库上该视图定义与本脚本内
--           CREATE VIEW 语句不完全一致(例如本地曾手工改过), 请先诊断差异原因, 不要
--           直接覆盖执行。
--
-- 明确不做: capacity.annual_discount_factor / annual_discount.fixed_discount_value /
--           equipment.annual_depreciation / equipment.hourly_depreciation /
--           global_variable_value.value_number 等边界列本次未纳入(需求文档 §3.6 待裁决
--           边界项, 用户未拍板), 维持原精度不变。
--
-- 数据影响: 仅放大 scale, 不重算、不截断、不改写业务值(历史值原样保留, 尾部补零);
--           不新增/删除表、索引或约束; 除 v_composite_child_elements 因列类型联动
--           需要 DROP/CREATE 外, 不新增/删除列。
--
-- 🚨 锁与上线: ALTER COLUMN TYPE 会重写表(表级排他锁, 期间该表读写均阻塞)。
--           目标表行数量级(dev 库实测参考, 生产库请自行核实): material_recipe_element
--           ~600 / unit_price ~400 / material_bom_item ~250 / element_bom_item ~200 /
--           其余 < 100。生产库建议在低峰或停写窗口执行; 执行前请备份本脚本涉及的
--           24 张表(至少备份 material_bom_item / element_bom_item / unit_price /
--           material_recipe_element 这几张行数较多的)。
--
-- 原子性: 86 列 DDL + 视图重建位于同一事务; 任一步失败则全部回滚。
-- 幂等性: 已是目标类型的列再次 ALTER 到相同类型是安全的; 视图 DROP 后 CREATE 同名同定义
--           同样可重复执行, 可安全重跑本脚本。
-- Flyway: 第 2 节只上调已有 BASELINE 行。若目标库没有 flyway_schema_history 表,
--         第 1 节结构仍已提交; 第 2 节报 relation does not exist 时跳过即可。
--
-- Navicat 导入: 右键库 -> 运行 SQL 文件 -> 选本文件 -> 勾选"遇到错误时停止"。
-- ============================================================


-- ============================================================
-- 执行前自检(建议先单独运行)
-- ------------------------------------------------------------
-- 1) 确认当前 Flyway 版本/基线(预期 385; 已是 387 表示无需执行)
--    SELECT max(version::int) FROM flyway_schema_history WHERE success;
--
-- 2) 确认目标列当前精度不足 12 位(首次执行预期非空, 列出所有待扩容列)
--    SELECT table_name||'.'||column_name AS col,
--           'numeric('||numeric_precision||','||numeric_scale||')' AS actual
--    FROM information_schema.columns
--    WHERE table_schema='public' AND data_type='numeric' AND numeric_scale IS NOT NULL
--      AND numeric_scale < 12
--      AND table_name IN ('material_bom_item','element_bom_item','unit_price','material_master',
--                         'material_recipe_element','plating_scheme','plating_fee','production_energy',
--                         'tooling_cost','capacity','auxiliary_energy','electricity_price','labor_rate',
--                         'fee_config','exchange_rate','exchange_rate_v6','material_customer_map',
--                         'element_daily_price','element_price','element_price_strategy',
--                         'element_price_version_item','process_master','production_consumable',
--                         'packaging_consumable')
--    ORDER BY 1;
--    -- 期望仅剩 capacity.annual_discount_factor(10,4) 一个边界列不在本次范围内(§3.6 待裁决,
--    -- 用户未拍板), 其余(含 element_price_strategy.factor)全部列出即待扩容列表。
--
-- 3) 确认 v_composite_child_elements 视图现存定义(备查, 供执行后 diff)
--    SELECT pg_get_viewdef('v_composite_child_elements'::regclass, true);
-- ============================================================


-- ============================================================
-- 第 1 节 · V386 + V387: 基础资料数值列扩为 12 位小数
-- ------------------------------------------------------------
-- V387 追加说明: element_price_strategy.factor 是定价乘数系数(原始价 × factor + premium,
-- StrategyService 校验 factor > 0), 与 unit_price.cost_ratio / capacity.cost_ratio 同族
-- (族 B 含量·占比·比率)。V386 §3.2 勘察时遗漏了这一列, 事后裁决补入, 与 V386 合并为一次
-- DDL 执行(该列的 ALTER 语句见下方 element_price_strategy 块内)。
--
-- ⚠️ 视图依赖勘察是在 dev 库 cpq_db_0724 上做的(见需求文档.md §5.1), 对该库准确, 但对
-- "内网这些走手工增量脚本、可能带历史包袱的库"不一定准确 —— 测试库 cpq_db 上另外发现
-- 2 个 task-0723 遗留的 _drop 后缀废弃视图(dev 库没有)会挡住下面的 ALTER COLUMN TYPE
-- (PG 报 "cannot alter type of a column used by a view or rule")。这两个视图由
-- V360__task0723_stage5 改名加 _drop 后缀标记待废弃, 全工程无业务代码引用, 已备份定义。
-- 新库(用 cpq-init-empty-navicat.sql 建的)不含它们, IF EXISTS 保证幂等、对干净库无副作用 ——
-- 这条防御只在这份手工增量脚本里需要, 不是画蛇添足。
-- ============================================================

BEGIN;

DROP VIEW IF EXISTS public.v_q_part_info_merged_drop;
DROP VIEW IF EXISTS public.v_costing_summary_full_drop;

DROP VIEW public.v_composite_child_elements;

ALTER TABLE public.auxiliary_energy
    ALTER COLUMN conversion_rate TYPE numeric(24,12),
    ALTER COLUMN non_production_energy_price TYPE numeric(24,12),
    ALTER COLUMN total_hours TYPE numeric(24,12),
    ALTER COLUMN working_hours TYPE numeric(24,12);

ALTER TABLE public.capacity
    ALTER COLUMN cost_ratio TYPE numeric(18,12),
    ALTER COLUMN default_defect_rate TYPE numeric(18,12),
    ALTER COLUMN fixed_cost TYPE numeric(24,12),
    ALTER COLUMN fixed_lead_time TYPE numeric(24,12),
    ALTER COLUMN variable_time TYPE numeric(24,12),
    ALTER COLUMN variable_time_batch TYPE numeric(24,12);

ALTER TABLE public.electricity_price
    ALTER COLUMN price TYPE numeric(24,12);

ALTER TABLE public.element_bom_item
    ALTER COLUMN base_qty TYPE numeric(24,12),
    ALTER COLUMN component_lead_time TYPE numeric(24,12),
    ALTER COLUMN composition_qty TYPE numeric(24,12),
    ALTER COLUMN content TYPE numeric(24,12),
    ALTER COLUMN defect_rate TYPE numeric(18,12),
    ALTER COLUMN fixed_scrap TYPE numeric(24,12),
    ALTER COLUMN lower_limit_pct TYPE numeric(18,12),
    ALTER COLUMN recovery_discount TYPE numeric(18,12),
    ALTER COLUMN scrap_batch TYPE numeric(24,12),
    ALTER COLUMN scrap_rate TYPE numeric(18,12),
    ALTER COLUMN upper_limit_pct TYPE numeric(18,12);

ALTER TABLE public.element_daily_price
    ALTER COLUMN raw_close TYPE numeric(26,12),
    ALTER COLUMN raw_high TYPE numeric(26,12),
    ALTER COLUMN raw_low TYPE numeric(26,12),
    ALTER COLUMN raw_open TYPE numeric(26,12),
    ALTER COLUMN raw_price TYPE numeric(26,12);

ALTER TABLE public.element_price
    ALTER COLUMN premium_price TYPE numeric(26,12);

ALTER TABLE public.element_price_strategy
    ALTER COLUMN premium TYPE numeric(26,12),
    ALTER COLUMN factor TYPE numeric(18,12);

ALTER TABLE public.element_price_version_item
    ALTER COLUMN change_rate TYPE numeric(18,12),
    ALTER COLUMN current_price TYPE numeric(26,12),
    ALTER COLUMN previous_price TYPE numeric(26,12);

ALTER TABLE public.exchange_rate
    ALTER COLUMN rate TYPE numeric(24,12);

ALTER TABLE public.exchange_rate_v6
    ALTER COLUMN rate TYPE numeric(22,12),
    ALTER COLUMN ref_rate TYPE numeric(22,12);

ALTER TABLE public.fee_config
    ALTER COLUMN ratio TYPE numeric(18,12),
    ALTER COLUMN value TYPE numeric(24,12);

ALTER TABLE public.labor_rate
    ALTER COLUMN standard_labor_rate TYPE numeric(24,12);

ALTER TABLE public.material_bom_item
    ALTER COLUMN base_qty TYPE numeric(24,12),
    ALTER COLUMN component_lead_time TYPE numeric(24,12),
    ALTER COLUMN composition_qty TYPE numeric(24,12),
    ALTER COLUMN defect_rate TYPE numeric(18,12),
    ALTER COLUMN fixed_scrap TYPE numeric(24,12),
    ALTER COLUMN lower_limit_pct TYPE numeric(18,12),
    ALTER COLUMN material_ratio TYPE numeric(24,12),
    ALTER COLUMN net_weight TYPE numeric(26,12),
    ALTER COLUMN recovery_discount TYPE numeric(18,12),
    ALTER COLUMN rough_weight TYPE numeric(26,12),
    ALTER COLUMN scrap_batch TYPE numeric(24,12),
    ALTER COLUMN scrap_rate TYPE numeric(18,12),
    ALTER COLUMN upper_limit_pct TYPE numeric(18,12);

ALTER TABLE public.material_customer_map
    ALTER COLUMN exchange_rate TYPE numeric(22,12);

ALTER TABLE public.material_master
    ALTER COLUMN unit_weight TYPE numeric(24,12);

ALTER TABLE public.material_recipe_element
    ALTER COLUMN default_pct TYPE numeric(16,12),
    ALTER COLUMN max_pct TYPE numeric(16,12),
    ALTER COLUMN min_pct TYPE numeric(16,12);

ALTER TABLE public.packaging_consumable
    ALTER COLUMN usage_qty TYPE numeric(24,12);

ALTER TABLE public.plating_fee
    ALTER COLUMN defect_rate TYPE numeric(18,12),
    ALTER COLUMN plating_material_fee TYPE numeric(26,12),
    ALTER COLUMN plating_process_fee TYPE numeric(26,12);

ALTER TABLE public.plating_scheme
    ALTER COLUMN density TYPE numeric(24,12),
    ALTER COLUMN element_usage TYPE numeric(24,12),
    ALTER COLUMN plating_area TYPE numeric(24,12),
    ALTER COLUMN plating_thickness TYPE numeric(24,12),
    ALTER COLUMN surface_area TYPE numeric(24,12);

ALTER TABLE public.process_master
    ALTER COLUMN default_defect_rate TYPE numeric(18,12);

ALTER TABLE public.production_consumable
    ALTER COLUMN usage_qty TYPE numeric(24,12);

ALTER TABLE public.production_energy
    ALTER COLUMN batch_size TYPE numeric(24,12),
    ALTER COLUMN conversion_rate TYPE numeric(24,12),
    ALTER COLUMN round_step TYPE numeric(24,12),
    ALTER COLUMN working_hours TYPE numeric(24,12);

ALTER TABLE public.tooling_cost
    ALTER COLUMN conversion_rate TYPE numeric(24,12),
    ALTER COLUMN cycle_output TYPE numeric(24,12),
    ALTER COLUMN tooling_unit_cost TYPE numeric(24,12),
    ALTER COLUMN tooling_unit_price TYPE numeric(22,12);

ALTER TABLE public.unit_price
    ALTER COLUMN base_value TYPE numeric(24,12),
    ALTER COLUMN conversion_rate TYPE numeric(24,12),
    ALTER COLUMN cost_ratio TYPE numeric(18,12),
    ALTER COLUMN defect_rate TYPE numeric(18,12),
    ALTER COLUMN fetched_price TYPE numeric(24,12),
    ALTER COLUMN market_ref_price TYPE numeric(24,12),
    ALTER COLUMN material_fixed_increase TYPE numeric(24,12),
    ALTER COLUMN material_increase_ratio TYPE numeric(18,12),
    ALTER COLUMN premium_fee TYPE numeric(24,12),
    ALTER COLUMN pricing_price TYPE numeric(24,12),
    ALTER COLUMN recovery_discount TYPE numeric(18,12);

-- 重建视图(V202 智能视图, 属 docs/三大核心模块基线.md §5.1 锁定范围; 定义与迁移前
-- 逐字节等价, 仅 ebi.content 的类型随上方 ALTER 从 numeric(18,6) 变为 numeric(24,12),
-- composition_pct 输出随之扩容)。
CREATE VIEW public.v_composite_child_elements AS
 SELECT ebi.hf_part_no,
    ebi.material_no AS child_hf_part_no,
    COALESCE(mm.material_name, ebi.material_no) AS child_part_name,
    0 AS child_seq,
    ebi.seq_no,
    ebi.component_no AS element_name,
    ebi.content AS composition_pct,
    c.id AS customer_id,
    NULL::uuid AS quotation_line_item_id
   FROM element_bom_item ebi
     LEFT JOIN material_master mm ON mm.material_no::text = ebi.material_no::text
     LEFT JOIN customer c ON c.code::text = ebi.customer_no::text
  WHERE ebi.system_type::text = 'QUOTE'::text
    AND ebi.hf_part_no IS NOT NULL
    AND ebi.is_current = true
    AND ebi.characteristic::text = ((
        SELECT max(ebi2.characteristic::text) AS max
          FROM element_bom_item ebi2
         WHERE ebi2.system_type::text = ebi.system_type::text
           AND ebi2.customer_no::text = ebi.customer_no::text
           AND ebi2.material_no::text = ebi.material_no::text));

COMMIT;


-- ============================================================
-- 第 2 节 · Flyway 基线上调(仅已有 BASELINE 行时生效)
-- ------------------------------------------------------------
-- 只改 BASELINE 且只从较低版本上调到 387。若该库由 Flyway 完整迁移、没有
-- BASELINE 行, 本语句影响 0 行; 后续 Quarkus 会幂等执行并登记正式 V386/V387 记录。
-- 若库中没有 flyway_schema_history 表, 本节报错时跳过, 第 1 节不受影响。
-- ============================================================

UPDATE public.flyway_schema_history
   SET version = '387',
       description = '<< Flyway Baseline >>',
       script = '<< Flyway Baseline >>'
 WHERE type = 'BASELINE'
   AND version IS NOT NULL
   AND version::int < 387;


-- ============================================================
-- 执行后自检(逐条核对期望值)
-- ============================================================
-- 1) 目标列已无残留 < 12 位精度的列(期望仅剩 2 行边界列, 见下)
--    SELECT table_name||'.'||column_name AS col,
--           'numeric('||numeric_precision||','||numeric_scale||')' AS actual
--    FROM information_schema.columns
--    WHERE table_schema='public' AND data_type='numeric' AND numeric_scale IS NOT NULL
--      AND numeric_scale < 12
--      AND table_name IN ('material_bom_item','element_bom_item','unit_price','material_master',
--                         'material_recipe_element','plating_scheme','plating_fee','production_energy',
--                         'tooling_cost','capacity','auxiliary_energy','electricity_price','labor_rate',
--                         'fee_config','exchange_rate','exchange_rate_v6','material_customer_map',
--                         'element_daily_price','element_price','element_price_strategy',
--                         'element_price_version_item','process_master','production_consumable',
--                         'packaging_consumable')
--    ORDER BY 1;
--    -- 期望仅 capacity.annual_discount_factor(10,4) 一行(边界列, 本次未纳入); 若出现
--    -- 其他行(含 element_price_strategy.factor), 说明有列漏扩容。
--
-- 2) v_composite_child_elements 视图定义与执行前自检第 3 步的输出逐字节一致(仅
--    composition_pct 的底层列类型变化, SELECT 文本本身不应有任何差异)
--    SELECT pg_get_viewdef('v_composite_child_elements'::regclass, true);
--
-- 3) Flyway 基线(有 BASELINE 行时期望 387; 无该行则 0 行)
--    SELECT version FROM flyway_schema_history WHERE type='BASELINE';
-- ============================================================
