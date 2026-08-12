-- ============================================================
-- CPQ 内网数据库增量更新 · 2026-08-11
-- ------------------------------------------------------------
-- 适用对象: 已跑过 deploy/0809-dbupdate.sql、表结构停在 Flyway V384 的内网库。
--           本脚本把表结构补齐到 V385。
--           若数据库由 2026-08-11 之后版本的 deploy/cpq-init-empty-navicat.sql 新建
--           (基线已是 385), 不需要跑本脚本。
--
-- 覆盖范围: Flyway V385。仅把 8 张表的 21 个计算金额列从
--           numeric(20,6) 扩为 numeric(26,12):
--           · quotation: 3 列
--           · quotation_line_item: 6 列
--           · quotation_line_component_data: 1 列
--           · costing_order: 2 列
--           · material_price_review: 1 列
--           · material_price_review_column: 6 列
--           · quotation_price_revision: 1 列
--           · material_price_update_job_item: 1 列
--
-- 数据影响: 仅放大 scale 6 -> 12, precision 20 -> 26, 整数容量仍为 14 位。
--           不重算、不截断、不改写业务值; 不新增/删除表、列、索引或约束。
--           基础价格、重量、费率、阈值等非计算结果字段不在本次范围。
--
-- 锁与上线: ALTER COLUMN TYPE 会取得表级锁。请在低峰或停写窗口执行;
--           执行前至少备份上述 8 张表。数据库脚本与 task-0810 应用版本同窗口上线。
--
-- 原子性: 21 列 DDL 位于同一事务; 任一列失败则全部回滚。
-- 幂等性: 已是 numeric(26,12) 的列再次 ALTER 到相同类型是安全的, 可重复执行。
-- Flyway: 第 2 节只上调已有 BASELINE 行。若目标库没有 flyway_schema_history 表,
--         第 1 节结构仍已提交; 第 2 节报 relation does not exist 时跳过即可。
--
-- Navicat 导入: 右键库 -> 运行 SQL 文件 -> 选本文件 -> 勾选"遇到错误时停止"。
-- ============================================================


-- ============================================================
-- 执行前自检(建议先单独运行)
-- ------------------------------------------------------------
-- 1) 确认当前 Flyway 版本/基线(预期 384; 已是 385 表示无需执行)
--    SELECT max(version::int) FROM flyway_schema_history WHERE success;
--
-- 2) 查看 21 个目标列当前定义(首次执行预期均为 20 / 6)
--    WITH target(table_name, column_name) AS (VALUES
--      ('quotation','total_amount'), ('quotation','original_amount'), ('quotation','tax_amount'),
--      ('quotation_line_item','subtotal'), ('quotation_line_item','discount_base_amount'),
--      ('quotation_line_item','line_unit_price'), ('quotation_line_item','line_final_price'),
--      ('quotation_line_item','line_discount_amount'), ('quotation_line_item','line_total_amount'),
--      ('quotation_line_component_data','subtotal'),
--      ('costing_order','total_amount'), ('costing_order','costing_total_amount'),
--      ('material_price_review','warn_diff'),
--      ('material_price_review_column','quote_current'),
--      ('material_price_review_column','quote_adjusted'),
--      ('material_price_review_column','costing_current'),
--      ('material_price_review_column','costing_adjusted'),
--      ('material_price_review_column','diff_current'),
--      ('material_price_review_column','diff_adjusted'),
--      ('quotation_price_revision','quote_total_amount'),
--      ('material_price_update_job_item','diff_value')
--    )
--    SELECT t.table_name, t.column_name, c.numeric_precision, c.numeric_scale
--      FROM target t
--      LEFT JOIN information_schema.columns c
--        ON c.table_schema='public' AND c.table_name=t.table_name AND c.column_name=t.column_name
--     ORDER BY t.table_name, t.column_name;
-- ============================================================


-- ============================================================
-- 第 1 节 · V385: 计算金额列扩为 12 位小数
-- ============================================================

BEGIN;

ALTER TABLE public.quotation
    ALTER COLUMN total_amount TYPE numeric(26,12),
    ALTER COLUMN original_amount TYPE numeric(26,12),
    ALTER COLUMN tax_amount TYPE numeric(26,12);

ALTER TABLE public.quotation_line_item
    ALTER COLUMN subtotal TYPE numeric(26,12),
    ALTER COLUMN discount_base_amount TYPE numeric(26,12),
    ALTER COLUMN line_unit_price TYPE numeric(26,12),
    ALTER COLUMN line_final_price TYPE numeric(26,12),
    ALTER COLUMN line_discount_amount TYPE numeric(26,12),
    ALTER COLUMN line_total_amount TYPE numeric(26,12);

ALTER TABLE public.quotation_line_component_data
    ALTER COLUMN subtotal TYPE numeric(26,12);

ALTER TABLE public.costing_order
    ALTER COLUMN total_amount TYPE numeric(26,12),
    ALTER COLUMN costing_total_amount TYPE numeric(26,12);

ALTER TABLE public.material_price_review
    ALTER COLUMN warn_diff TYPE numeric(26,12);

ALTER TABLE public.material_price_review_column
    ALTER COLUMN quote_current TYPE numeric(26,12),
    ALTER COLUMN quote_adjusted TYPE numeric(26,12),
    ALTER COLUMN costing_current TYPE numeric(26,12),
    ALTER COLUMN costing_adjusted TYPE numeric(26,12),
    ALTER COLUMN diff_current TYPE numeric(26,12),
    ALTER COLUMN diff_adjusted TYPE numeric(26,12);

ALTER TABLE public.quotation_price_revision
    ALTER COLUMN quote_total_amount TYPE numeric(26,12);

ALTER TABLE public.material_price_update_job_item
    ALTER COLUMN diff_value TYPE numeric(26,12);

COMMIT;


-- ============================================================
-- 第 2 节 · Flyway 基线上调(仅已有 BASELINE 行时生效)
-- ------------------------------------------------------------
-- 只改 BASELINE 且只从较低版本上调到 385。若该库由 Flyway 完整迁移、没有
-- BASELINE 行, 本语句影响 0 行; 后续 Quarkus 会幂等执行并登记正式 V385 记录。
-- 若库中没有 flyway_schema_history 表, 本节报错时跳过, 第 1 节不受影响。
-- ============================================================

UPDATE public.flyway_schema_history
   SET version = '385',
       description = '<< Flyway Baseline >>',
       script = '<< Flyway Baseline >>'
 WHERE type = 'BASELINE'
   AND version IS NOT NULL
   AND version::int < 385;


-- ============================================================
-- 执行后自检(逐条核对期望值)
-- ============================================================
-- 1) 21 个目标列全部为 numeric(26,12)(期望 21)
--    WITH target(table_name, column_name) AS (VALUES
--      ('quotation','total_amount'), ('quotation','original_amount'), ('quotation','tax_amount'),
--      ('quotation_line_item','subtotal'), ('quotation_line_item','discount_base_amount'),
--      ('quotation_line_item','line_unit_price'), ('quotation_line_item','line_final_price'),
--      ('quotation_line_item','line_discount_amount'), ('quotation_line_item','line_total_amount'),
--      ('quotation_line_component_data','subtotal'),
--      ('costing_order','total_amount'), ('costing_order','costing_total_amount'),
--      ('material_price_review','warn_diff'),
--      ('material_price_review_column','quote_current'),
--      ('material_price_review_column','quote_adjusted'),
--      ('material_price_review_column','costing_current'),
--      ('material_price_review_column','costing_adjusted'),
--      ('material_price_review_column','diff_current'),
--      ('material_price_review_column','diff_adjusted'),
--      ('quotation_price_revision','quote_total_amount'),
--      ('material_price_update_job_item','diff_value')
--    )
--    SELECT count(*) FROM target t JOIN information_schema.columns c
--      ON c.table_schema='public' AND c.table_name=t.table_name AND c.column_name=t.column_name
--     WHERE c.numeric_precision=26 AND c.numeric_scale=12;
--
-- 2) 缺列或精度不符明细(期望 0 行)
--    WITH target(table_name, column_name) AS (VALUES
--      ('quotation','total_amount'), ('quotation','original_amount'), ('quotation','tax_amount'),
--      ('quotation_line_item','subtotal'), ('quotation_line_item','discount_base_amount'),
--      ('quotation_line_item','line_unit_price'), ('quotation_line_item','line_final_price'),
--      ('quotation_line_item','line_discount_amount'), ('quotation_line_item','line_total_amount'),
--      ('quotation_line_component_data','subtotal'),
--      ('costing_order','total_amount'), ('costing_order','costing_total_amount'),
--      ('material_price_review','warn_diff'),
--      ('material_price_review_column','quote_current'),
--      ('material_price_review_column','quote_adjusted'),
--      ('material_price_review_column','costing_current'),
--      ('material_price_review_column','costing_adjusted'),
--      ('material_price_review_column','diff_current'),
--      ('material_price_review_column','diff_adjusted'),
--      ('quotation_price_revision','quote_total_amount'),
--      ('material_price_update_job_item','diff_value')
--    )
--    SELECT t.table_name, t.column_name, c.numeric_precision, c.numeric_scale
--      FROM target t LEFT JOIN information_schema.columns c
--        ON c.table_schema='public' AND c.table_name=t.table_name AND c.column_name=t.column_name
--     WHERE c.column_name IS NULL OR c.numeric_precision<>26 OR c.numeric_scale<>12
--     ORDER BY t.table_name, t.column_name;
--
-- 3) Flyway 基线(有 BASELINE 行时期望 385; 无该行则 0 行)
--    SELECT version FROM flyway_schema_history WHERE type='BASELINE';
-- ============================================================
