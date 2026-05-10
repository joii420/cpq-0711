-- V26: v4 基础字典层 — ProductCategory + ComparisonTag + Product.category_id

-- ========== ProductCategory: 产品分类字典 ==========
CREATE TABLE IF NOT EXISTS product_category (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code        VARCHAR(50)  NOT NULL UNIQUE,
    name        VARCHAR(100) NOT NULL,
    description TEXT,
    parent_id   UUID REFERENCES product_category(id),
    status      VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    sort_order  INTEGER      NOT NULL DEFAULT 0,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_product_category_parent ON product_category(parent_id);
CREATE INDEX IF NOT EXISTS idx_product_category_status ON product_category(status);

-- 种子: 从现有 product.category 去重创建
INSERT INTO product_category (code, name, sort_order)
SELECT DISTINCT
       UPPER(REPLACE(category, ' ', '_')) AS code,
       category                            AS name,
       0
FROM   product
WHERE  category IS NOT NULL AND category <> ''
ON CONFLICT (code) DO NOTHING;

-- 兜底: 至少有一个默认分类
INSERT INTO product_category (code, name, sort_order)
VALUES ('DEFAULT', '默认分类', 999)
ON CONFLICT (code) DO NOTHING;

-- ========== Product.category_id: 关联到 ProductCategory ==========
ALTER TABLE product ADD COLUMN IF NOT EXISTS category_id UUID REFERENCES product_category(id);

-- 回填: product.category 字符串 -> product.category_id
UPDATE product p
SET    category_id = pc.id
FROM   product_category pc
WHERE  pc.name = p.category
  AND  p.category_id IS NULL;

-- 仍为空的兜底到 DEFAULT
UPDATE product p
SET    category_id = (SELECT id FROM product_category WHERE code = 'DEFAULT')
WHERE  p.category_id IS NULL;

CREATE INDEX IF NOT EXISTS idx_product_category_id ON product(category_id);

-- 注: 保留 product.category 字符串列以兼容旧代码，新代码统一用 category_id

-- ========== ComparisonTag: 业务标签字典 ==========
CREATE TABLE IF NOT EXISTS comparison_tag (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code              VARCHAR(80)  NOT NULL UNIQUE,
    label             VARCHAR(200) NOT NULL,
    group_name        VARCHAR(100) NOT NULL,
    group_sort_order  INTEGER      NOT NULL DEFAULT 0,
    tag_sort_order    INTEGER      NOT NULL DEFAULT 0,
    is_builtin        BOOLEAN      NOT NULL DEFAULT FALSE,
    status            VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    description       TEXT,
    created_at        TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_comparison_tag_group ON comparison_tag(group_name);
CREATE INDEX IF NOT EXISTS idx_comparison_tag_status ON comparison_tag(status);

-- 内置业务标签种子
INSERT INTO comparison_tag (code, label, group_name, group_sort_order, tag_sort_order, is_builtin) VALUES
  ('MATERIAL_COST_AG',    'Ag材料成本',  '材料成本维度', 1, 1, TRUE),
  ('MATERIAL_COST_CU',    'Cu材料成本',  '材料成本维度', 1, 2, TRUE),
  ('MATERIAL_COST_TOTAL', '总材料成本',  '材料成本维度', 1, 99, TRUE),
  ('PROCESSING_COST',     '加工费',     '加工费维度',   2, 1, TRUE),
  ('LABOR_COST',          '人工成本',    '加工费维度',   2, 2, TRUE),
  ('SETUP_COST',          '设置成本',    '加工费维度',   2, 3, TRUE),
  ('OVERHEAD_COST',       '管理费用',    '其他费用',     3, 1, TRUE),
  ('PACKAGING_COST',      '包装费',     '其他费用',     3, 2, TRUE),
  ('CUSTOM_COST',         '自定义费用',  '其他费用',     3, 99, TRUE),
  ('UNIT_TOTAL_COST',     '单位总成本',  '汇总',         9, 1, TRUE),
  ('TOTAL',               '总价',       '汇总',         9, 2, TRUE)
ON CONFLICT (code) DO NOTHING;
