-- V28: CostingTemplate + Template 加 customer_id/category_id

-- ========== CostingTemplate: 核价模板（按产品分类绑定） ==========
CREATE TABLE IF NOT EXISTS costing_template (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    series_id             UUID NOT NULL,
    name                  VARCHAR(200) NOT NULL,
    category_id           UUID NOT NULL REFERENCES product_category(id),
    is_default            BOOLEAN NOT NULL DEFAULT FALSE,
    version               VARCHAR(20) NOT NULL DEFAULT 'v1.0',
    status                VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    description           TEXT,
    columns               JSONB NOT NULL DEFAULT '[]'::jsonb,
    referenced_variables  JSONB NOT NULL DEFAULT '[]'::jsonb,
    created_by            UUID,
    published_at          TIMESTAMP WITH TIME ZONE,
    created_at            TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_costing_template_category ON costing_template(category_id);
CREATE INDEX IF NOT EXISTS idx_costing_template_series ON costing_template(series_id);
CREATE INDEX IF NOT EXISTS idx_costing_template_status ON costing_template(status);

-- 部分唯一索引: 同一分类下只能有一个默认模板
CREATE UNIQUE INDEX IF NOT EXISTS uq_costing_template_default
    ON costing_template(category_id) WHERE is_default = TRUE;

-- ========== Template: 加 customer_id + category_id ==========
ALTER TABLE template ADD COLUMN IF NOT EXISTS customer_id UUID REFERENCES customer(id);
ALTER TABLE template ADD COLUMN IF NOT EXISTS category_id UUID REFERENCES product_category(id);

-- 回填 category_id: 根据 template.category 字符串匹配 product_category.name
UPDATE template t
SET    category_id = pc.id
FROM   product_category pc
WHERE  pc.name = t.category
  AND  t.category_id IS NULL;

-- 仍为空的兜底到 DEFAULT 分类
UPDATE template t
SET    category_id = (SELECT id FROM product_category WHERE code = 'DEFAULT')
WHERE  t.category_id IS NULL;

CREATE INDEX IF NOT EXISTS idx_template_customer ON template(customer_id);
CREATE INDEX IF NOT EXISTS idx_template_category ON template(category_id);

-- 在创建唯一索引前，先归档重复的 PUBLISHED 模板，仅保留每组最新发布的一个
WITH ranked AS (
    SELECT id,
           ROW_NUMBER() OVER (
             PARTITION BY COALESCE(customer_id::text, ''), category_id
             ORDER BY published_at DESC NULLS LAST, created_at DESC
           ) AS rn
    FROM template
    WHERE status = 'PUBLISHED' AND category_id IS NOT NULL
)
UPDATE template SET status = 'ARCHIVED'
WHERE id IN (SELECT id FROM ranked WHERE rn > 1);

-- 部分唯一索引（v4 spec 4.11）:
--   通用模板（customer_id IS NULL）每分类仅一个 PUBLISHED
--   客户专属模板（customer_id NOT NULL）同 (customer, category) 仅一个 PUBLISHED
CREATE UNIQUE INDEX IF NOT EXISTS uq_template_general_published
    ON template(category_id) WHERE customer_id IS NULL AND status = 'PUBLISHED';
CREATE UNIQUE INDEX IF NOT EXISTS uq_template_customer_published
    ON template(customer_id, category_id) WHERE customer_id IS NOT NULL AND status = 'PUBLISHED';
