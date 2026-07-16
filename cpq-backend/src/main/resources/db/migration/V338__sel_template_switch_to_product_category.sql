-- V338__sel_template_switch_to_product_category.sql
-- task-0712 update-071501: 选配模板换轴 industry_code -> product_category_id。
-- 背景: dev-docs/task-0712-选配模板和报价单选配功能/update-071501/backtask.md B1.2
-- 决策(D6/D12): sel_template 一产品分类一套(UNIQUE，沿用原一行业一套的唯一约束语义)；
-- 存量 sel_template/_item/_item_value 均为测试数据，换轴前清空重配，避免多行业模板
-- 换轴后都撞到"默认分类"的唯一约束。
--
-- ⚠️ 共享 cpq_db 多会话并发: 全部语句幂等，允许本迁移被重跑而不报错。
-- ⚠️ DDL(DROP/ADD COLUMN) 后必须 touch 一个 java 文件强制 Quarkus 重启，清进程级缓存
--    (参照 CLAUDE.md「视图 DROP CASCADE/重建后必须重启」同理)。

-- 清空存量(测试数据；子表先清，避免 FK 冲突，尽管有 ON DELETE CASCADE 兜底)
DELETE FROM sel_template_item_value;
DELETE FROM sel_template_item;
DELETE FROM sel_template;

-- 换轴: 删旧 industry_code(内联 UNIQUE 约束随列一并删除)，加 product_category_id
ALTER TABLE sel_template DROP COLUMN IF EXISTS industry_code;
ALTER TABLE sel_template ADD COLUMN IF NOT EXISTS product_category_id UUID NOT NULL;

-- UNIQUE 约束(一产品分类一套)；守卫防重复添加
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'sel_template_product_category_uk') THEN
        ALTER TABLE sel_template
            ADD CONSTRAINT sel_template_product_category_uk UNIQUE (product_category_id);
    END IF;
END $$;

-- 软引用: 按项目惯例不加物理 FK 指向 product_category(应用层校验)
