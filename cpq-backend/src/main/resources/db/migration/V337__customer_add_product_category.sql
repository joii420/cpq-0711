-- V337__customer_add_product_category.sql
-- task-0712 update-071501: 三模板统一到「客户产品分类」轴 —— 客户加字段 + backfill。
-- 背景: dev-docs/task-0712-选配模板和报价单选配功能/update-071501/backtask.md B1
-- 决策: 产品分类做成客户身上的绑定(D1/D2)；存量客户 backfill 成"默认分类"杜绝空值(D3)。
--
-- ⚠️ 共享 cpq_db 多会话并发: 全部语句幂等(IF NOT EXISTS / WHERE NOT EXISTS 守卫)，
-- 允许本迁移在列/数据已存在的状态下被重跑而不报错(参照 V336 的惯例)。

-- 0) 前置: 保障"默认分类"存在(幂等 seed；code 用约定值 DEFAULT)
INSERT INTO product_category (id, code, name, status, sort_order, created_at, updated_at)
SELECT gen_random_uuid(), 'DEFAULT', '默认分类', 'ACTIVE', 0, now(), now()
WHERE NOT EXISTS (SELECT 1 FROM product_category WHERE name = '默认分类');

-- 1) 加列(先可空，便于 backfill)
ALTER TABLE customer ADD COLUMN IF NOT EXISTS product_category_id UUID;

-- 2) backfill: 所有现有客户刷成"默认分类"(D3 杜绝空值)
UPDATE customer
   SET product_category_id = (SELECT id FROM product_category WHERE name = '默认分类' LIMIT 1)
 WHERE product_category_id IS NULL;

-- 3) 置非空约束(backfill 后；对已是 NOT NULL 的列重跑是无害 no-op)
ALTER TABLE customer ALTER COLUMN product_category_id SET NOT NULL;

-- 软引用: 按项目惯例不加物理 FK(应用层校验，见 backtask.md B1 注)
