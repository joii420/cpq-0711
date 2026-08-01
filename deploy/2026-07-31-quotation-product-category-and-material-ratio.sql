-- =============================================================================
-- 报价单产品分类 + 物料BOM材质占比 表结构同步脚本（内网/生产手工同步用，非 Flyway 管理时）
-- =============================================================================
-- 来源    : master
--           = 迁移 V364__quotation_product_category_id.sql
--           + 迁移 V365__material_bom_item_add_material_ratio.sql 的最终态
-- 生成日期: 2026-07-31
--
-- 覆盖对象（两处加列，无新表、无索引、无约束变更）:
--   1. quotation.product_category_id        uuid            —— 建单时的产品分类（task-0729）
--   2. material_bom_item.material_ratio     numeric(18,6)   —— 材质占比（报价侧物料BOM 可选列）
--
-- 前置    : quotation、material_bom_item 两表须已存在。
-- 特性    : 幂等、可重复执行（两条均 ADD COLUMN IF NOT EXISTS，COMMENT 覆盖写）。
--           对「全新库」与「已建库」均安全；不回填存量数据（两列存量均为 NULL，见下方语义说明）。
--
-- 如果内网走 Flyway：不要跑本文件，直接把 V364/V365 两个迁移放进 db/migration 让 Flyway 跑。
-- 已手工跑过本文件、之后又启用 Flyway：也安全 —— 两个迁移体本身就是 IF NOT EXISTS，
--           Flyway 补记账时重复执行不会失败（但仍建议届时核对 flyway_schema_history）。
-- =============================================================================

BEGIN;

-- -----------------------------------------------------------------------------
-- 1) quotation.product_category_id —— 建单时的产品分类
-- -----------------------------------------------------------------------------
-- 语义：记录「建这张单时」的产品分类，**不追溯客户改绑**（守 D4）。
-- 存量：不回填。为 NULL 时前端回落「从 customer_template_id 反查模板分类」，故老单不受影响。
ALTER TABLE quotation ADD COLUMN IF NOT EXISTS product_category_id uuid;

COMMENT ON COLUMN quotation.product_category_id IS
  'task-0729: 建单时的产品分类(不追溯客户改绑, 守 D4)。存量不回填, 为 NULL 时前端回落"从 customer_template_id 反查模板分类"。';

-- -----------------------------------------------------------------------------
-- 2) material_bom_item.material_ratio —— 材质占比
-- -----------------------------------------------------------------------------
-- 语义：报价侧「物料BOM」sheet 的**可选列**「材质占比」，小数口径（0.3 = 30%），
--       与 element_bom_item.content 同口径同精度；**不是** scrap_rate/defect_rate 那种百分数值。
-- 归属：仅材质行（characteristic='RECIPE'）有值 —— 零件/外购件行由导入 handler 显式置 NULL；
--       核价侧（system_type='PRICING'）不写该列，恒 NULL。
-- 约束：非必填 → nullable、无 CHECK。「同一销售料号下多材质占比合计=1」在非必填前提下
--       无法在库层强制（部分行为空时约束必然误伤），该校验属业务/公式层。
-- 唯一键：不动 uq_material_bom_item —— 占比是内容列，不是键列。
-- 存量：不回填，本脚本执行后该列全为 NULL；要出数须业务在导入文件补该列后**重导**。
ALTER TABLE material_bom_item ADD COLUMN IF NOT EXISTS material_ratio numeric(18,6);

COMMENT ON COLUMN material_bom_item.material_ratio IS
  '材质占比(小数口径, 0.3=30%); 报价侧物料BOM 材质行(characteristic=RECIPE)可选填, 零件/外购件行与核价侧恒 NULL';

COMMIT;

-- =============================================================================
-- 执行后自检（期望两行，data_type / 精度如下）
-- =============================================================================
-- SELECT table_name, column_name, data_type, numeric_precision, numeric_scale, is_nullable
--   FROM information_schema.columns
--  WHERE (table_name = 'quotation'         AND column_name = 'product_category_id')
--     OR (table_name = 'material_bom_item' AND column_name = 'material_ratio')
--  ORDER BY table_name;
--
-- 期望：
--   material_bom_item | material_ratio      | numeric | 18 | 6 | YES
--   quotation         | product_category_id | uuid    |    |   | YES
-- =============================================================================
