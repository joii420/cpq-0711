-- V399__backfill_sel_param_type_seed.sql
-- 补回 sel_param_type 的 3 行封闭枚举种子。
--
-- 【为什么需要这条迁移】
-- 这 3 行原本由 V313__sel_template_tables.sql 的 INSERT 灌入。但基线在 V313 之后
-- 建立的库(dev 库 cpq_db_0724 基线 = V361; 用 deploy/cpq-init-empty-navicat.sql
-- 新建的库基线 = V398)不会重放 V313, 而空库版建库脚本只建表未带种子 ——
-- 结果这些库里 sel_param_type 恒为 0 行。
--
-- 【症状】选配模板管理 → 新建模板, 弹「参数池加载中，请稍候再试」且永远不会好
--         (GET /api/cpq/sel-param-types 返回 200 + data:[], 前端把「空」误判为「加载中」)。
--
-- 【为什么不能靠用户在界面上配】
-- data_source_key / persist_handler_key 的取值与 Java 侧 handler key 强耦合 ——
-- SelParamCandidateService 用 switch (pt.dataSourceKey) 直接匹配 MATERIAL_RECIPE /
-- V6_PROCESS_MASTER。这是封闭枚举, 系统未提供也不应提供维护界面, 只能随库交付。
--
-- 幂等: ON CONFLICT DO NOTHING —— 已有 3 行的库(如老库 cpq_db)跑本迁移是空操作,
--       且不会覆盖任何已存在行。

INSERT INTO sel_param_type (code, name, value_mode, data_source_key, persist_handler_key, sort_order) VALUES
  ('MATERIAL', '材质',    'single', 'MATERIAL_RECIPE',   'MATERIAL_RECIPE_BIND', 1),
  ('ELEMENT',  '元素含量', 'adjust', NULL,                'ELEMENT_OVERRIDE',     2),
  ('PROCESS',  '工序',    'multi',  'V6_PROCESS_MASTER', 'PROCESS_LIST',         3)
ON CONFLICT (code) DO NOTHING;
