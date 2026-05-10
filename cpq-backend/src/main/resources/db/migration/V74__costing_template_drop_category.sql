-- V74: costing_template 移除 category_id —— 按 linked_template_id 调用
--
-- 用户原话："Excel模板配置移除产品分类字段，根据关联的模板进行调用"。
-- 即 Excel 模板不再按产品分类组织，而是按 linked_template_id 关联的具体模板调用。
--
-- 原 uq_costing_template_default UNIQUE (category_id) WHERE is_default = true 必须先删除（该索引依赖 category_id 列）。
-- 新增 uq_costing_template_default UNIQUE (linked_template_id) WHERE is_default = true ——
-- 即同一个关联模板下最多只能有一份"默认 Excel 模板"。linked_template_id IS NULL 的行不参与 partial unique。
--
-- 数据兼容性：当前 costing_template 全部 3 行；唯一一行 is_default=true 已绑定 linked_template_id，迁移无冲突。

DROP INDEX IF EXISTS uq_costing_template_default;
DROP INDEX IF EXISTS idx_costing_template_category;

ALTER TABLE costing_template
    DROP CONSTRAINT IF EXISTS costing_template_category_id_fkey;

ALTER TABLE costing_template
    DROP COLUMN IF EXISTS category_id;

CREATE UNIQUE INDEX IF NOT EXISTS uq_costing_template_default
    ON costing_template(linked_template_id)
    WHERE is_default = true;
