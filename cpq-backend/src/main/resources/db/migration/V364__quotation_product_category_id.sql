ALTER TABLE quotation ADD COLUMN IF NOT EXISTS product_category_id uuid;
COMMENT ON COLUMN quotation.product_category_id IS
  'task-0729: 建单时的产品分类(不追溯客户改绑, 守 D4)。存量不回填, 为 NULL 时前端回落"从 customer_template_id 反查模板分类"。';
