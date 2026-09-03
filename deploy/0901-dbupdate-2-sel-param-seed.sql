-- ============================================================
-- CPQ 内网数据库增量更新 · 2026-09-01 (第 2 份) —— sel_param_type 种子补齐 (V399)
-- ------------------------------------------------------------
-- 【先看这条: 多数情况下你不需要手工跑本脚本】
-- 本脚本内容与后端迁移 V399__backfill_sel_param_type_seed.sql 完全一致。
-- 内网环境只要**更新后端到含 V399 的版本并重启**, Flyway 会自动补齐这 3 行, 无需人工介入。
-- 仅当"不想等发版、要立刻恢复选配模板管理"时, 才手工跑本脚本 —— 跑过之后再发版也安全,
-- 因为两边都是 ON CONFLICT DO NOTHING, V399 届时是空操作。
--
-- 【适用对象】任何 sel_param_type 为 0 行的库。典型是:
--   · 基线晚于 V313 的库(V313 的种子 INSERT 不会重放), 例如 dev 库 cpq_db_0724(基线 361);
--   · 用 2026-09-01 之前版本的 deploy/cpq-init-empty-navicat.sql 新建的库
--     (那一版只建表未带种子; 该脚本已于本次同步补上, 之后新建的库不受影响)。
--
-- 【症状】选配模板管理 → 新建模板, 弹「参数池加载中，请稍候再试」且永远不会好。
--         GET /api/cpq/sel-param-types 返回 200 但 data 为 []。
--
-- 【为什么这 3 行不能让用户在界面上自己配】
-- data_source_key / persist_handler_key 与 Java 侧 handler key 强耦合 ——
-- SelParamCandidateService 用 switch (pt.dataSourceKey) 直接匹配 MATERIAL_RECIPE /
-- V6_PROCESS_MASTER。这是代码依赖的封闭枚举, 系统未提供也不应提供维护界面。
--
-- 【风险】无。纯 INSERT + ON CONFLICT DO NOTHING:
--   · 不含任何 DDL、DELETE、UPDATE, 不触碰任何既有行;
--   · 库里已有同 code 的行则原样保留, 绝不覆盖;
--   · 可重复执行, 第二次起影响 0 行。
-- ============================================================

-- ---- 执行前自检: 看看现在有几行(期望 0; 若已是 3 行则无需执行) ----
--    SELECT count(*) FROM sel_param_type;

BEGIN;

INSERT INTO sel_param_type (code, name, value_mode, data_source_key, persist_handler_key, sort_order) VALUES
  ('MATERIAL', '材质',    'single', 'MATERIAL_RECIPE',   'MATERIAL_RECIPE_BIND', 1),
  ('ELEMENT',  '元素含量', 'adjust', NULL,                'ELEMENT_OVERRIDE',     2),
  ('PROCESS',  '工序',    'multi',  'V6_PROCESS_MASTER', 'PROCESS_LIST',         3)
ON CONFLICT (code) DO NOTHING;

COMMIT;

-- ============================================================
-- 执行后自检
-- ------------------------------------------------------------
-- 1) 3 行齐全且取值正确(期望恰 3 行, 逐字如下)
--    SELECT code, name, value_mode, data_source_key, persist_handler_key, sort_order
--      FROM sel_param_type ORDER BY sort_order;
--    -- 期望:
--    --   MATERIAL | 材质     | single | MATERIAL_RECIPE   | MATERIAL_RECIPE_BIND | 1
--    --   ELEMENT  | 元素含量 | adjust | (NULL)            | ELEMENT_OVERRIDE     | 2
--    --   PROCESS  | 工序     | multi  | V6_PROCESS_MASTER | PROCESS_LIST         | 3
--
-- 2) 接口恢复(登录后调, 期望 data 内 3 个对象而非 [])
--    GET /api/cpq/sel-param-types
--
-- 3) 界面恢复: 选配模板管理 → 新建模板, 应正常打开抽屉并列出「材质 / 元素含量 / 工序」三项,
--    而不是再弹「参数池加载中，请稍候再试」。
--
-- ⚠️ 注意: 本脚本**不写 flyway_schema_history**。这是刻意的 ——
--    留着让后端发版时 V399 正常执行(届时 ON CONFLICT 使其成为空操作), 版本账目才不会错位。
-- ============================================================
